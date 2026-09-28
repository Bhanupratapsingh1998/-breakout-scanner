package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.index500.pattern.PatternRegistry;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Both universes are served by one controller, so this is the test that the sharing is real.
 *
 * <p>It asserts two things that a cloned controller would get for free and a shared one must earn:
 * that {@code /api/index500/...} still answers exactly as it did, and that {@code /api/fno/...}
 * answers the same shapes rather than 404ing on half the endpoints. The scan itself is not driven
 * here - it walks 500 symbols over the network - so these are the routing and empty-state
 * contracts, which are what the path change could break.
 *
 * <p>Standalone MockMvc, for the same reason as the other API tests: the full context needs a
 * Postgres datasource for the journal, and a routing test that needs a database is a test that
 * stops being run.
 *
 * <p>One caveat, stated rather than hidden: the {@code /universe} size assertions reach NSE, via
 * the same lazy membership fetch the running app uses. Every assertion here is written to hold
 * whether that fetch succeeds or falls back to the bundled list, so the test passes offline - it
 * is simply slower online. Making it fully hermetic would mean a seam through {@code SectorService}
 * that exists only for this test.
 */
class UniverseRoutingTest {

    private final Index500Config cfg = new Index500Config();
    private final SectorService sectors = new SectorService();
    private final PatternRegistry registry = new PatternRegistry(new BullishConfig());

    /**
     * The detectors are wired for real but the wick collaborators are left null: nothing here
     * analyses a bar, and a null that would NPE the moment a scan touched it is a louder failure
     * than a mock that quietly returns nothing.
     */
    private Index500AnalysisService service() {
        return new Index500AnalysisService(cfg, sectors, new PatternAnalysisService(registry),
                new OpportunityScoreService(), null, null, null);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.standaloneSetup(new Index500AnalysisController(
                service(), sectors, new SectorAnalysisService(), new PerformanceRankingService(),
                registry, cfg)).build();
    }

    @Test
    void bothUniversesAnswerTheirOwnPaths() throws Exception {
        MockMvc mvc = mvc();
        for (String slug : new String[] { "index500", "fno" }) {
            // No scan has run, so the data endpoints are 404 - the cold-start case the tab renders
            // as "has not run yet", not a routing failure.
            mvc.perform(get("/api/" + slug + "/analysis")).andExpect(status().isNotFound());
            mvc.perform(get("/api/" + slug + "/sector-summary")).andExpect(status().isNotFound());

            // These answer without a scan, which is what lets the empty state say something useful.
            mvc.perform(get("/api/" + slug + "/status"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.running").value(false));
            mvc.perform(get("/api/" + slug + "/patterns"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.patterns").isMap());
        }
    }

    @Test
    void theUniverseEndpointDescribesWhichListIsInPlay() throws Exception {
        mvc().perform(get("/api/index500/universe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("index500"))
                .andExpect(jsonPath("$.hasLots").value(false))
                // An equity universe is never "stale": there is no snapshot to fall back to.
                .andExpect(jsonPath("$.live").value(true));

        mvc().perform(get("/api/fno/universe"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").value("fno"))
                .andExpect(jsonPath("$.shortLabel").value("F&O stocks"))
                .andExpect(jsonPath("$.hasLots").value(true))
                // Around 210 names, from NSE or from the bundled snapshot. Either way the tab has
                // a universe before anything has been scanned.
                .andExpect(jsonPath("$.size").value(org.hamcrest.Matchers.greaterThan(150)));
    }

    @Test
    void aUniverseThatIsNotOursDoesNotRoute() throws Exception {
        // The slug is pinned in the path pattern, so a typo never reaches a handler.
        mvc().perform(get("/api/nifty50/analysis")).andExpect(status().isNotFound());
        mvc().perform(post("/api/everything/scan")).andExpect(status().isNotFound());
    }

    @Test
    void scanningOneUniverseDoesNotBlockTheOther() {
        Index500AnalysisService service = service();

        // The whole point of keying run state by universe. With one shared AtomicBoolean the second
        // call here would return false and the F&O tab would sit on "queued" forever.
        assertTrue(service.triggerScan(Universe.NIFTY_500));
        assertTrue(service.triggerScan(Universe.FNO));

        // ...while a second run of the SAME universe is still refused.
        assertFalse(service.triggerScan(Universe.NIFTY_500));
    }

    @Test
    void eachUniverseKeepsItsOwnResult() {
        Index500AnalysisService service = service();

        // Nothing has run, so neither has a table - and asking for one must not create the other's.
        assertNull(service.lastRun(Universe.NIFTY_500));
        assertNull(service.lastRun(Universe.FNO));
        assertNull(service.generatedAt(Universe.FNO));
    }

    @Test
    void slugsResolveAndTypoesDoNot() {
        assertEquals(Universe.NIFTY_500, Universe.ofSlug("index500"));
        assertEquals(Universe.FNO, Universe.ofSlug("fno"));
        assertEquals(Universe.FNO, Universe.ofSlug("FNO"));
        assertThrows(IllegalArgumentException.class, () -> Universe.ofSlug("nifty500"));
    }

    @Test
    void theFnoUniverseCarriesLotSizesAndTheEquityOneDoesNot() {
        // Lot size is what makes the F&O tab an F&O tab rather than another stock list, and it must
        // not leak into the equity universe, where it would be a column of dashes.
        assertTrue(sectors.universe(Universe.FNO).stream().anyMatch(StockMetadata::hasDerivatives)
                        || sectors.universe(Universe.FNO).stream().allMatch(m -> m.lotSize() == 0),
                "F&O rows carry lots when NSE answered, and none when the snapshot was used");
        assertTrue(sectors.universe(Universe.NIFTY_500).stream().noneMatch(StockMetadata::hasDerivatives));
    }
}
