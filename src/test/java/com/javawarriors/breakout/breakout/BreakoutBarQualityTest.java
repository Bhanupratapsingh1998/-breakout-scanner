package com.javawarriors.breakout.breakout;

import com.javawarriors.breakout.model.Bar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BreakoutBarQualityTest {

    private static List<Bar> one(double o, double h, double l, double c) {
        return List.of(new Bar(1_700_000_000L, o, h, l, c, 1_000_000));
    }

    @Test
    void wideBodiedBarClosingOnItsHighIsStrong() {
        // range 10, body 9, closes at the top, no upper wick.
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(100, 110, 100, 109), 0, 6);

        assertEquals("STRONG", q.rating());
        assertEquals(4, q.score(), "all four readings pass");
        assertTrue(q.confirms());
        assertEquals(0.90, q.closePosition(), 0.01);
        assertEquals(0.90, q.bodyRatio(), 0.01);
        assertEquals(0.10, q.upperWickRatio(), 0.01);
    }

    /** The failed breakout: new high tagged, then sold back to the open. */
    @Test
    void aDominantUpperWickIsWeakEvenWhenOtherReadingsPass() {
        // range 20, body 2 at the bottom, 18 of upper wick.
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(100, 120, 100, 102), 0, 5);

        assertEquals("WEAK", q.rating(), "the high was rejected");
        assertFalse(q.confirms());
        assertTrue(q.upperWickRatio() >= 0.40);
    }

    @Test
    void aBarClosingBelowItsOpenIsNeverStrong() {
        // Big range, small upper wick, but red: cleared resistance intraday and gave it back.
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(110, 112, 100, 102), 0, 5);

        assertEquals("WEAK", q.rating());
        assertFalse(q.confirms(), "a red breakout candle is not confirmation");
    }

    @Test
    void anIndecisionBarWithLongWicksBothSidesIsNotStrong() {
        // doji-ish: range 10, body 0.5, centred.
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(105, 110, 100, 105.5), 0, 6);

        assertNotEquals("STRONG", q.rating());
        assertTrue(q.bodyRatio() < 0.5, "no real body means no conviction");
    }

    @Test
    void aNarrowRangeBarLosesTheExpansionPointButCanStillBeStrong() {
        // Clean bar, but range 2 against ATR 8 -- drifting through the level, not breaking out.
        BreakoutBarQuality strongNarrow = BreakoutBarQuality.analyze(one(100, 102, 100, 102), 0, 8);

        assertTrue(strongNarrow.rangeVsAtr() < 1.0);
        assertEquals(3, strongNarrow.score(), "loses only the expansion point");
        assertEquals("STRONG", strongNarrow.rating());
    }

    @Test
    void aDecentCloseWithNoBodyAndNoExpansionScoresFair() {
        // range 10: closes at 80% (pass) with only a 20% upper wick (pass), but the body is 40%
        // of range (fail) and the range is 0.67x ATR (fail) -- two of four.
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(104, 110, 100, 108), 0, 15);

        assertEquals("FAIR", q.rating());
        assertEquals(2, q.score());
        assertFalse(q.confirms(), "only STRONG confirms");
        assertTrue(q.upperWickRatio() < 0.40, "not a rejection, just an unconvincing bar");
    }

    /** A mid-range close after tagging a new high is a rejection, not a merely average bar. */
    @Test
    void closingAtMidRangeAfterANewHighIsWeak() {
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(101, 110, 100, 105), 0, 6);

        assertEquals("WEAK", q.rating());
        assertEquals(0.50, q.upperWickRatio(), 0.01, "half the bar is upper wick");
    }

    @Test
    void missingOrImpossibleInputsReportUnknownInsteadOfNaN() {
        assertFalse(BreakoutBarQuality.analyze(one(1, 2, 0.5, 1.5), -1, 5).isKnown());
        assertFalse(BreakoutBarQuality.analyze(null, 0, 5).isKnown());
        assertFalse(BreakoutBarQuality.analyze(one(1, 2, 0.5, 1.5), 99, 5).isKnown());

        // Circuit-locked bar: high == low, so every ratio would divide by zero.
        BreakoutBarQuality flat = BreakoutBarQuality.analyze(one(100, 100, 100, 100), 0, 5);
        assertFalse(flat.isKnown());
        assertFalse(flat.toRow().containsKey("closePositionPct"), "no numbers offered for an unknown read");
    }

    @Test
    void anUnusableAtrDropsTheExpansionReadingWithoutPoisoningTheRest() {
        BreakoutBarQuality q = BreakoutBarQuality.analyze(one(100, 110, 100, 109), 0, Double.NaN);

        assertTrue(Double.isNaN(q.rangeVsAtr()));
        assertEquals(3, q.score(), "the other three still score");
        assertEquals("STRONG", q.rating());
        assertFalse(q.toRow().containsKey("rangeVsAtr"), "an unusable reading is omitted, not zeroed");
    }
}
