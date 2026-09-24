package com.javawarriors.breakout.bullish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What is left of the bullish API after the Bullish Stocks tab was removed: one endpoint, scoring
 * one symbol.
 *
 * <p>It is not dead code kept for tidiness. My Watchlist calls it for every followed stock, so the
 * row shape asserted below is the contract that tab renders against - which is why these tests
 * check the whole breakdown rather than just a 200.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest}: the application context needs a
 * Postgres datasource for the trade journal, and an API-shape test that cannot run without a
 * database is a test that stops being run.
 */
class BullishStocksControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BullishConfig cfg = new BullishConfig();

    /** One real analysis, served as though the symbol had just been looked up. */
    private Map<String, Object> sampleRow() {
        // 265 bars: past bullish.min-bars=250, which is what the engine needs for EMA200 plus the
        // 126-session six-month return window.
        List<Bar> bars = SeriesBuilder.startingAt(100)
                .move(220, 35).chop(40, 2, 0.8).strongUpBar(8, 3.0).move(4, 2).build();
        List<Bar> index = SeriesBuilder.startingAt(20000).move(300, 20).build();

        Regime regime = MarketRegimeAnalyzer.analyze(index, index, cfg);
        BullishStockResult result = new BullishStockAnalyzer(cfg)
                .analyze("TEST.NS", "Test Company", "NIFTY_500", "IT", bars, index, index, regime);

        Map<String, Object> row = result.toRow();
        row.put("marketRegime", regime.toRow());
        return row;
    }

    private MockMvc mvcServing(Map<String, Object> row) {
        BullishStocksService stub = new BullishStocksService(cfg) {
            @Override
            public Map<String, Object> analyzeOne(String symbol) {
                return row;
            }
        };
        return MockMvcBuilders.standaloneSetup(new BullishStocksController(stub)).build();
    }

    @Test
    void oneSymbolCarriesTheFullBreakdownPatternPlanAndExplanation() throws Exception {
        // Every field My Watchlist's table and its expanded panel read.
        mvcServing(sampleRow()).perform(get("/api/bullish-stocks/TEST.NS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("TEST.NS"))
                .andExpect(jsonPath("$.score").isNumber())
                .andExpect(jsonPath("$.classification").exists())
                .andExpect(jsonPath("$.tradeStatus").exists())
                .andExpect(jsonPath("$.setupStage").exists())
                .andExpect(jsonPath("$.whyBullish").isNotEmpty())
                .andExpect(jsonPath("$.scoreBreakdown.components.trend.points").isNumber())
                .andExpect(jsonPath("$.pattern.name").exists())
                .andExpect(jsonPath("$.trend.label").exists())
                .andExpect(jsonPath("$.momentum.rsi").isNumber())
                .andExpect(jsonPath("$.volume.currentRatio").isNumber())
                .andExpect(jsonPath("$.relativeStrength.excess3mPct").isNumber())
                .andExpect(jsonPath("$.overextension.level").exists())
                .andExpect(jsonPath("$.tradePlan.present").exists())
                .andExpect(jsonPath("$.marketRegime").exists());
    }

    @Test
    void aScoreNeverExceedsOneHundredInTheServedPayload() {
        JsonNode row = mapper.valueToTree(sampleRow());
        double score = row.get("score").asDouble();

        assertTrue(score >= 0 && score <= 100, "score out of range: " + score);
        JsonNode components = row.get("scoreBreakdown").get("components");
        components.fields().forEachRemaining(e -> {
            double points = e.getValue().get("points").asDouble();
            double max = e.getValue().get("maxPoints").asDouble();
            assertTrue(points <= max, e.getKey() + " scored " + points + " of " + max);
        });
    }

    @Test
    void aSymbolThatCannotBeFetchedFailsAsABadGatewayNotAServerError() throws Exception {
        BullishStocksService failing = new BullishStocksService(cfg) {
            @Override
            public Map<String, Object> analyzeOne(String symbol) throws java.io.IOException {
                throw new java.io.IOException("upstream is down");
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BullishStocksController(failing)).build();

        mvc.perform(get("/api/bullish-stocks/NOSUCH.NS"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void theRankingEndpointsWentWithTheTab() throws Exception {
        // The list, the scan trigger and the status poll all belonged to the removed tab. Asserting
        // they are gone keeps them from creeping back as dead routes nothing calls.
        MockMvc mvc = mvcServing(sampleRow());

        // "/api/bullish-stocks" now matches nothing; only "/{symbol}" is mapped.
        mvc.perform(post("/api/bullish-stocks/scan")).andExpect(status().is4xxClientError());
        mvc.perform(get("/api/bullish-stocks")).andExpect(status().is4xxClientError());

        // And the retired names must 404 rather than being read as tickers. Without this guard
        // "/status" is a symbol called STATUS.NS, and a stale poller sends a live upstream fetch
        // every 1.5 seconds to be answered with a 502.
        mvc.perform(get("/api/bullish-stocks/status")).andExpect(status().isNotFound());
        mvc.perform(get("/api/bullish-stocks/results")).andExpect(status().isNotFound());
    }

    @Test
    void anOrdinarySymbolIsStillLookedUpNormally() throws Exception {
        // The guard above must not turn into a filter that eats real tickers.
        mvcServing(sampleRow()).perform(get("/api/bullish-stocks/TATAELXSI.NS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").isNumber());
    }
}
