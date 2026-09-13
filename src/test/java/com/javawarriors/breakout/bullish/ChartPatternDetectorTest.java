package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ChartPatternDetectorTest {

    private final BullishConfig cfg = new BullishConfig();

    @Test
    void aPoleAndAQuietShallowDriftIsABullFlag() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(150, 20)                 // history for the moving averages
                .move(18, 28, 1.8)             // the pole: sharp advance on heavy volume
                .chop(10, 1.2, 0.5)            // the flag: tight and quiet
                .snapshot();

        ChartPattern p = ChartPatternDetector.bullFlag(s, cfg);

        assertEquals("BULL_FLAG", p.type());
        assertTrue(p.confidence() >= cfg.getMinPatternConfidence());
        assertTrue(p.breakoutLevel() > p.resistance(), "the buffer must sit above the flag high");
        assertTrue(p.target() > p.breakoutLevel(), "the measured move projects above the break");
        assertTrue(p.invalidationLevel() < p.resistance());
    }

    @Test
    void aDeepPullbackAfterAPoleIsNotAFlag() {
        // Gives back well over half the pole - a failed advance, not a flag.
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(150, 20)
                .move(18, 28, 1.8)
                .move(10, -22, 1.5)
                .snapshot();

        assertFalse(ChartPatternDetector.bullFlag(s, cfg).isPresent(),
                "a deep, heavy pullback must not be classified as a bull flag");
    }

    @Test
    void aFlagOnExpandingVolumeIsRejected() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(150, 20)
                .move(18, 28, 1.0)
                .chop(10, 1.2, 3.0)            // same shape, but volume is rising in the flag
                .snapshot();

        assertFalse(ChartPatternDetector.bullFlag(s, cfg).isPresent(),
                "heavy volume inside the consolidation contradicts the pattern");
    }

    @Test
    void aTightRangeUnderTheHighsAfterAnAdvanceIsAFlatBase() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(120, 15)
                .move(60, 30, 1.5)             // the prior advance the base pauses
                .chop(35, 2.5, 0.6)            // the base itself
                .snapshot();

        ChartPattern p = ChartPatternDetector.flatBase(s, cfg);

        assertEquals("FLAT_BASE", p.type());
        assertTrue(p.resistance() > 0);
        assertTrue(p.invalidationLevel() < p.resistance());
    }

    @Test
    void aTightRangeWithNoPriorAdvanceIsNotAFlatBase() {
        // The test that stops every quiet stock in the universe matching: same tight range,
        // but nothing came before it.
        IndicatorSnapshot s = SeriesBuilder.flatMarket(300).snapshot();

        assertFalse(ChartPatternDetector.flatBase(s, cfg).isPresent());
    }

    @Test
    void twoLowsAtTheSameLevelWithARallyBetweenIsADoubleBottom() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(200)
                .move(120, -5)
                .move(30, -25)                 // first leg down to the low
                .move(25, 22)                  // the rally that forms the neckline
                .move(25, -18)                 // back down to roughly the same low
                .move(20, 20)                  // recovering off it
                .snapshot();

        ChartPattern p = ChartPatternDetector.doubleBottom(s, cfg);

        assertEquals("DOUBLE_BOTTOM", p.type());
        assertTrue(p.resistance() > p.invalidationLevel(), "the neckline sits above the lows");
        assertTrue(p.target() > p.resistance());
    }

    @Test
    void aFeaturelessSeriesProducesNoClearPatternRatherThanTheNearestFit() {
        ChartPattern p = ChartPatternDetector.detect(SeriesBuilder.flatMarket(300).snapshot(), cfg);

        assertFalse(p.isPresent());
        assertEquals("NO_CLEAR_PATTERN", p.type());
        assertEquals(0, p.points());
        assertTrue(Double.isNaN(p.breakoutLevel()), "a non-pattern must not publish levels");
    }

    @Test
    void confidenceNeverReachesOneHundred() {
        // Whatever the evidence, a pattern is an observation and not a certainty.
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(150, 20).move(18, 35, 2.0).chop(8, 0.8, 0.3).snapshot();

        ChartPattern p = ChartPatternDetector.detect(s, cfg);
        if (p.isPresent()) {
            assertTrue(p.confidence() <= 95, "confidence is capped at 95, got " + p.confidence());
            assertTrue(p.points() <= ChartPatternDetector.MAX_POINTS);
        }
    }

    @Test
    void raisingTheConfidenceFloorSuppressesWeakerDetections() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(150, 20).move(18, 28, 1.8).chop(10, 1.2, 0.5).snapshot();

        BullishConfig strict = new BullishConfig();
        strict.setMinPatternConfidence(99);

        assertTrue(ChartPatternDetector.detect(s, cfg).isPresent());
        assertFalse(ChartPatternDetector.detect(s, strict).isPresent(),
                "the floor is configuration, and it must actually gate the result");
    }
}
