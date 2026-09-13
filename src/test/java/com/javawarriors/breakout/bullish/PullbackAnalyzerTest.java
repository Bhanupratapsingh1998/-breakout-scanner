package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;
import com.javawarriors.breakout.bullish.PullbackAnalyzer.SetupStage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The decision tree, tested directly against hand-built inputs.
 *
 * <p>Going through the full analyzer would make these tests depend on whether a synthetic series
 * happened to produce a particular ATR, which is not what is being asserted. What matters here is
 * the precedence between the checks, so the stages and overextension readings are constructed.
 */
class PullbackAnalyzerTest {

    private final BullishConfig cfg = new BullishConfig();
    private final IndicatorSnapshot snapshot = SeriesBuilder.healthyUptrend().snapshot();

    private static Regime regime(String name) {
        MarketRegimeAnalyzer.IndexState unknown = MarketRegimeAnalyzer.IndexState.unknown("test");
        return new Regime(name, 8, 10, 1.0, !"BEARISH".equals(name), "test", unknown, unknown);
    }

    private static BreakoutStage stage(boolean confirmed, boolean near, boolean failed, double distancePct) {
        return new BreakoutStage(confirmed ? "FIFTY_TWO_WEEK" : "CONSOLIDATION", "test",
                confirmed, near, failed, 100, 100.5, confirmed ? 5 : -1, confirmed ? 4 : -1,
                distancePct, 2.0, 0.9, 8, "test");
    }

    private static Overextension over(String level) {
        return new Overextension(level, 1, 2, 3, 4, 60, "NORMAL", List.of(), level + " for the test");
    }

    @Test
    void aFailedBreakoutIsAFailedSetupWhateverElseIsTrue() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, true, -2),
                over("NONE"), regime("BULLISH"), 98, 5.0, null, cfg);

        assertEquals(PullbackAnalyzer.FAILED_SETUP, s.tradeStatus());
        assertEquals(PullbackAnalyzer.FAILED_BREAKOUT, s.stage());
        assertFalse(s.tradeable(), "a perfect score cannot rescue a broken breakout");
    }

    @Test
    void severeOverextensionOverridesEvenAnAPlusScore() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 20),
                over("SEVERE"), regime("BULLISH"), 97, 6.0, null, cfg);

        assertEquals(PullbackAnalyzer.AVOID_CHASING, s.tradeStatus());
        assertFalse(s.tradeable());
    }

    @Test
    void moderateOverextensionBecomesWaitForPullback() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 13),
                over("MODERATE"), regime("BULLISH"), 90, 4.0, null, cfg);

        assertEquals(PullbackAnalyzer.WAIT_FOR_PULLBACK, s.tradeStatus());
        assertFalse(s.tradeable());
    }

    @Test
    void aCleanConfirmedBreakoutWithAGoodPlanIsTheOnlyPathToBuyNow() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 4),
                over("NONE"), regime("BULLISH"), 82, 3.2, null, cfg);

        assertEquals(PullbackAnalyzer.BUY_NOW, s.tradeStatus());
        assertTrue(s.tradeable());
    }

    @Test
    void aHighScoreAloneNeverProducesBuyNow() {
        // Section 12, stated as a test: the same 95-point stock with no valid trade plan.
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 4),
                over("NONE"), regime("BULLISH"), 95, Double.NaN, null, cfg);

        assertNotEquals(PullbackAnalyzer.BUY_NOW, s.tradeStatus());
        assertFalse(s.tradeable());
        assertTrue(s.reason().contains("no trade plan"), s.reason());
    }

    @Test
    void riskRewardBelowTheMinimumBlocksTheEntry() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 4),
                over("NONE"), regime("BULLISH"), 88, 1.4, null, cfg);

        assertEquals(PullbackAnalyzer.WAIT_FOR_RETEST, s.tradeStatus());
        assertTrue(s.reason().contains("1.4:1"), s.reason());
    }

    @Test
    void aBearishMarketWithholdsEntriesWithoutHidingTheSetup() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 4),
                over("NONE"), regime("BEARISH"), 92, 4.0, null, cfg);

        assertNotEquals(PullbackAnalyzer.BUY_NOW, s.tradeStatus());
        assertEquals(PullbackAnalyzer.BREAKOUT_CONFIRMED, s.stage(),
                "the setup is still reported for what it is");
        assertTrue(s.reason().contains("bearish"), s.reason());
    }

    @Test
    void aHoldingRetestIsEligibleForBuyNow() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 1.2),
                over("NONE"), regime("BULLISH"), 80, 3.5, null, cfg);

        assertEquals(PullbackAnalyzer.BREAKOUT_RETEST, s.stage());
        assertEquals(PullbackAnalyzer.BUY_NOW, s.tradeStatus());
    }

    @Test
    void aRetestThatFailsTheScoreFloorWaitsRatherThanBuying() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 1.2),
                over("NONE"), regime("BULLISH"), 55, 3.5, null, cfg);

        assertEquals(PullbackAnalyzer.BREAKOUT_RETEST, s.stage());
        assertEquals(PullbackAnalyzer.WAIT_FOR_RETEST, s.tradeStatus());
    }

    @Test
    void nothingBrokenYetMeansWaitForBreakout() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(false, true, false, -1.5),
                over("NONE"), regime("BULLISH"), 78, 4.0, null, cfg);

        assertEquals(PullbackAnalyzer.READY_TO_BREAKOUT, s.stage());
        assertEquals(PullbackAnalyzer.WAIT_FOR_BREAKOUT, s.tradeStatus());
    }

    @Test
    void anUnalignedTrendCannotProduceAnEntry() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 3),
                over("NONE"), regime("BULLISH"), 90, 5.0,
                "price is not above all three rising moving averages", cfg);

        assertEquals(PullbackAnalyzer.WAIT_FOR_BREAKOUT, s.tradeStatus());
        assertFalse(s.tradeable());
    }

    /**
     * Regression: a stock stacked on the daily but inside a falling weekly trend used to be told
     * "price is not above all three moving averages", contradicting the explanation printed beside
     * it, which said the opposite. The blocker has to name itself.
     */
    @Test
    void theReasonNamesTheActualBlockerRatherThanBlamingTheEmaStack() {
        SetupStage s = PullbackAnalyzer.classify(snapshot, stage(true, false, false, 3),
                over("NONE"), regime("BULLISH"), 90, 5.0,
                "the daily stack is aligned but the weekly or monthly trend still points down", cfg);

        assertEquals(PullbackAnalyzer.WAIT_FOR_BREAKOUT, s.tradeStatus());
        assertTrue(s.reason().contains("weekly or monthly"), s.reason());
        assertFalse(s.reason().contains("not above all three"),
                "must not blame the EMA stack when the stack is fine: " + s.reason());
    }
}
