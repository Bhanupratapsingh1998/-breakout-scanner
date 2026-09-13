package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BreakoutStageAnalyzerTest {

    private final BullishConfig cfg = new BullishConfig();

    private BreakoutStage analyze(IndicatorSnapshot s) {
        return BreakoutStageAnalyzer.analyze(s, ChartPattern.none(), cfg);
    }

    @Test
    void aCloseClearOfTheRangeTopOnHeavyVolumeIsAConfirmedBreakout() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30)
                .chop(40, 2, 0.8)              // the range that forms resistance
                .strongUpBar(8, 3.0)           // the break: decisive close, heavy volume
                .move(4, 2, 1.4)               // it holds
                .snapshot();

        BreakoutStage b = analyze(s);

        assertTrue(b.confirmed());
        assertFalse(b.failed());
        assertTrue(b.distancePct() > 0);
        assertTrue(b.breakoutVolumeRatio() > cfg.getBreakoutVolumeRatio(),
                "volume ratio " + b.breakoutVolumeRatio());
        assertTrue(b.points() >= 6, "a clean, confirmed break should score well, got " + b.points());
    }

    @Test
    void anIntradaySpikeThatClosedBackInsideTheRangeIsNotABreakout() {
        // Section 7's explicit warning. The high cleared the level; the close did not.
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30)
                .chop(40, 2, 0.8)
                .upperWickBar(6, 2.5)
                .snapshot();

        BreakoutStage b = analyze(s);

        assertFalse(b.confirmed(), "a wick above resistance is not a breakout");
    }

    @Test
    void aBreakoutOnThinVolumeEarnsTheLevelButNotTheQualityPoints() {
        IndicatorSnapshot heavy = SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 2, 1.0).strongUpBar(8, 3.0).move(4, 2).snapshot();
        IndicatorSnapshot thin = SeriesBuilder.startingAt(100)
                .move(180, 30).chop(40, 2, 1.0).strongUpBar(8, 0.4).move(4, 2).snapshot();

        BreakoutStage strong = analyze(heavy);
        BreakoutStage weak = analyze(thin);

        assertTrue(strong.confirmed());
        assertTrue(weak.confirmed(), "the level was still cleared on a close");
        assertTrue(strong.points() > weak.points(),
                "volume must separate them: " + strong.points() + " vs " + weak.points());
    }

    @Test
    void closingBackUnderTheClearedLevelIsAFailedBreakout() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30)
                .chop(40, 2, 0.8)
                .strongUpBar(8, 3.0)
                .move(10, -14, 1.5)            // straight back down through the level
                .snapshot();

        BreakoutStage b = analyze(s);

        assertTrue(b.failed(), "price closed back below the level it cleared");
        assertEquals(0, b.points(), "a failed breakout earns nothing");
        assertEquals("FAILED BREAKOUT", b.label());
    }

    @Test
    void aStockCoilingJustUnderResistanceReadsAsApproaching() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(180, 30)
                .chop(40, 1.2, 0.7)
                .snapshot();

        BreakoutStage b = analyze(s);

        assertFalse(b.confirmed());
        assertTrue(b.nearBreakout(), "distance was " + b.distancePct() + "%");
        assertTrue(b.distancePct() <= 0);
    }

    @Test
    void theStrongestClearedLevelIsTheOneReported() {
        // A stock at new 52-week highs has also cleared its consolidation and six-month levels;
        // the reading should be the strongest of them, not the first one checked.
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(260, 90, 1.2)
                .strongUpBar(4, 2.5)
                .snapshot();

        BreakoutStage b = analyze(s);

        assertTrue(b.confirmed());
        assertEquals("FIFTY_TWO_WEEK", b.type());
        assertEquals("52-WEEK BREAKOUT", b.label());
    }
}
