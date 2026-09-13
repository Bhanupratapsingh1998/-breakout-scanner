package com.javawarriors.breakout.bullish;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.IndexState;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The API contract, exercised through real MVC routing with a stubbed service.
 *
 * <p>Standalone MockMvc rather than {@code @SpringBootTest}: the application context needs a
 * Postgres datasource for the trade journal, and an API-shape test that cannot run without a
 * database is a test that stops being run. Routing, status codes and the JSON shape are what this
 * needs to cover, and standalone setup covers all three.
 */
class BullishStocksControllerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final BullishConfig cfg = new BullishConfig();

    /** A service whose scan has already produced a payload built from one real analysis. */
    private BullishStocksService serviceWithResults() {
        return new BullishStocksService(cfg) {
            @Override
            public Map<String, Object> results() {
                return samplePayload();
            }
        };
    }

    private Map<String, Object> samplePayload() {
        // 265 bars: past bullish.min-bars=250, which is what the engine needs for EMA200 plus the
        // 126-session six-month return window.
        List<Bar> bars = SeriesBuilder.startingAt(100)
                .move(220, 35).chop(40, 2, 0.8).strongUpBar(8, 3.0).move(4, 2).build();
        List<Bar> index = SeriesBuilder.startingAt(20000).move(300, 20).build();

        Regime regime = MarketRegimeAnalyzer.analyze(index, index, cfg);
        BullishStockResult result = new BullishStockAnalyzer(cfg)
                .analyze("TEST.NS", "Test Company", "NIFTY_500", "IT", bars, index, index, regime);

        List<BullishStockResult> ranked = new ArrayList<>();
        ranked.add(result);
        return BullishStocksService.buildPayload(ranked, regime, 500, 3, cfg);
    }

    @Test
    void theRankedListCarriesEverythingTheDashboardHeaderNeeds() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new BullishStocksController(serviceWithResults())).build();

        mvc.perform(get("/api/bullish-stocks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.marketRegime.regime").exists())
                .andExpect(jsonPath("$.analyzedStockCount").value(1))
                .andExpect(jsonPath("$.universeSize").value(500))
                .andExpect(jsonPath("$.bullishStockCount").exists())
                .andExpect(jsonPath("$.aPlusCount").exists())
                .andExpect(jsonPath("$.breakoutCount").exists())
                .andExpect(jsonPath("$.pullbackCount").exists())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.stocks").isArray());
    }

    @Test
    void eachStockCarriesItsFullBreakdownPatternPlanAndExplanation() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new BullishStocksController(serviceWithResults())).build();

        String json = mvc.perform(get("/api/bullish-stocks"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode stock = mapper.readTree(json).get("stocks").get(0);

        assertEquals(1, stock.get("rank").asInt());
        assertEquals("TEST.NS", stock.get("symbol").asText());
        assertEquals("Test Company", stock.get("name").asText());
        assertTrue(stock.has("score"));
        assertTrue(stock.has("classification"));
        assertTrue(stock.has("tradeStatus"));
        assertTrue(stock.has("setupStage"));
        assertFalse(stock.get("whyBullish").asText().isBlank(), "section 14 asks for the why");

        JsonNode components = stock.get("scoreBreakdown").get("components");
        assertEquals(8, components.size(), "all eight components must reach the dashboard");
        for (String name : new String[] {"trend", "relativeStrength", "momentum", "volume",
                "priceStructure", "patternQuality", "breakoutQuality", "riskReward"}) {
            assertTrue(components.has(name), "missing component " + name);
            assertTrue(components.get(name).has("points"));
            assertTrue(components.get(name).has("maxPoints"));
        }

        for (String name : new String[] {"trend", "momentum", "relativeStrength", "volume",
                "pattern", "breakout", "overextension", "tradePlan", "higherTimeframes"}) {
            assertTrue(stock.has(name), "missing section " + name);
        }

        JsonNode summary = stock.get("summary");
        for (String name : new String[] {"patternName", "trendLabel", "rsLabel", "rsi", "adx",
                "volumeRatio", "entry", "stopLoss", "target", "riskReward"}) {
            assertTrue(summary.has(name), "the table row needs a flat " + name);
        }
    }

    @Test
    void aScoreNeverExceedsOneHundredInTheServedPayload() throws Exception {
        JsonNode stock = mapper.valueToTree(samplePayload()).get("stocks").get(0);
        double score = stock.get("score").asDouble();

        assertTrue(score >= 0 && score <= 100, "score was " + score);
    }

    @Test
    void noResultsYetIsAFourOhFourRatherThanAnEmptyList() throws Exception {
        BullishStocksService empty = new BullishStocksService(cfg) {
            @Override
            public Map<String, Object> results() {
                return null;
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BullishStocksController(empty)).build();

        mvc.perform(get("/api/bullish-stocks")).andExpect(status().isNotFound());
    }

    @Test
    void aSecondScanRequestWhileOneIsRunningIsRejected() throws Exception {
        BullishStocksService busy = new BullishStocksService(cfg) {
            @Override
            public boolean triggerScan() {
                return false;
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new BullishStocksController(busy)).build();

        mvc.perform(post("/api/bullish-stocks/scan"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("already-running"));
    }

    @Test
    void statusReportsWhetherAScanIsRunning() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new BullishStocksController(new BullishStocksService(cfg))).build();

        mvc.perform(get("/api/bullish-stocks/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.running").value(false))
                .andExpect(jsonPath("$.cachedSeries").exists());
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
}
