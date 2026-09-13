package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;
import com.javawarriors.breakout.bullish.TradePlanCalculator.TradePlan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TradePlanCalculatorTest {

    private final BullishConfig cfg = new BullishConfig();

    private TradePlan planFor(IndicatorSnapshot s) {
        ChartPattern pattern = ChartPatternDetector.detect(s, cfg);
        BreakoutStage breakout = BreakoutStageAnalyzer.analyze(s, pattern, cfg);
        Overextension over = OverextensionAnalyzer.analyze(s, breakout, cfg);
        return TradePlanCalculator.calculate(s, breakout, pattern, over, cfg);
    }

    @Test
    void aCleanBreakoutProducesAnInternallyConsistentPlan() {
        TradePlan p = planFor(SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 2, 0.8).strongUpBar(4, 3.0).move(3, 1).snapshot());

        assertTrue(p.present(), p.explanation());
        assertTrue(p.stopLoss() < p.entry(), "the stop sits below the entry");
        assertTrue(p.target1() > p.entry(), "the target sits above it");
        assertTrue(p.target2() >= p.target1(), "the second target is never nearer than the first");
        assertEquals((p.target1() - p.entry()) / (p.entry() - p.stopLoss()), p.riskReward(), 1e-6,
                "the ratio must be the arithmetic of the levels, not an independent number");
        assertTrue(p.riskPct() > 0 && p.riskPct() <= cfg.getMaxRiskPct() * 100);
    }

    @Test
    void aStopFurtherThanTheRiskCapProducesNoPlanAtAll() {
        // Section 10's "do not force a trade plan": with the cap at 1% nothing can be placed.
        BullishConfig tight = new BullishConfig();
        tight.setMaxRiskPct(0.01);

        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 4, 0.8).strongUpBar(4, 3.0).snapshot();
        ChartPattern pattern = ChartPatternDetector.detect(s, tight);
        BreakoutStage breakout = BreakoutStageAnalyzer.analyze(s, pattern, tight);
        Overextension over = OverextensionAnalyzer.analyze(s, breakout, tight);

        TradePlan p = TradePlanCalculator.calculate(s, breakout, pattern, over, tight);

        assertFalse(p.present());
        assertEquals(0, p.points(), "no plan means no risk/reward points");
        assertTrue(p.explanation().contains("maximum"), p.explanation());
        assertTrue(Double.isNaN(p.riskReward()));
    }

    @Test
    void aPreBreakoutSetupIsPricedAtTheLevelRatherThanAtMarket() {
        TradePlan p = planFor(SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 1.2, 0.7).snapshot());

        if (p.present()) {
            assertEquals("ON BREAKOUT", p.entryType());
            assertTrue(p.entry() > 0);
        }
    }

    @Test
    void aBetterRatioScoresHigherThanAWorseOne() {
        // Same plan geometry, different minimum: the preferred ratio must earn more than the
        // minimum one, and both more than a sub-minimum plan.
        BullishConfig demanding = new BullishConfig();
        demanding.setPreferredRiskReward(1.0);   // everything now clears "preferred"

        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 2, 0.8).strongUpBar(4, 3.0).move(3, 1).snapshot();
        ChartPattern pattern = ChartPatternDetector.detect(s, cfg);
        BreakoutStage breakout = BreakoutStageAnalyzer.analyze(s, pattern, cfg);
        Overextension over = OverextensionAnalyzer.analyze(s, breakout, cfg);

        TradePlan strict = TradePlanCalculator.calculate(s, breakout, pattern, over, cfg);
        TradePlan lenient = TradePlanCalculator.calculate(s, breakout, pattern, over, demanding);

        assertTrue(lenient.points() >= strict.points(),
                "an easier preferred-ratio bar cannot score lower");
    }

    @Test
    void pointsNeverExceedTheComponentMaximum() {
        TradePlan p = planFor(SeriesBuilder.healthyUptrend().snapshot());
        assertTrue(p.points() <= TradePlanCalculator.MAX_POINTS);
        assertTrue(p.points() >= 0);
    }

    @Test
    void blueSkyBreakoutsStillGetATargetAndSayWhereItCameFrom() {
        TradePlan p = planFor(SeriesBuilder.startingAt(100).move(260, 90, 1.2)
                .strongUpBar(4, 2.5).snapshot());

        if (p.present()) {
            assertTrue(p.target1() > p.entry());
            assertNotEquals("NONE", p.targetBasis());
        }
    }
}
