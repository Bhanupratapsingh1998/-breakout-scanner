package com.javawarriors.breakout.breakout;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StretchFromMeanTest {

    /** The case that prompted this: Redington at 392, EMA50 330, EMA200 280, ATR 14.64. */
    @Test
    void redingtonAtItsHighReadsAsAChase() {
        StretchFromMean s = StretchFromMean.analyze(392.00, 329.98, 279.55, 14.64, 242.7);

        assertEquals("CHASE", s.rating());
        assertTrue(s.isChase());
        assertEquals(4.2, s.atrsAboveEma50(), 0.1, "4+ ATRs above the 50 EMA");
        assertEquals(18.8, s.pctAboveEma50(), 0.5);
        assertEquals(40.2, s.pctAboveEma200(), 0.5);
        assertEquals(61.5, s.runUp63d(), 1.0, "a 60% three-month run");
    }

    @Test
    void aStockSittingOnItsMeanIsNormal() {
        StretchFromMean s = StretchFromMean.analyze(100, 99, 90, 3, 95);

        assertEquals("NORMAL", s.rating());
        assertFalse(s.isChase());
        assertEquals(0.33, s.atrsAboveEma50(), 0.05);
    }

    @Test
    void aStockBelowItsMeanIsNeverAChase() {
        StretchFromMean s = StretchFromMean.analyze(80, 100, 110, 4, 120);

        assertEquals("NORMAL", s.rating(), "trading under the 50 EMA has not run anywhere");
        assertFalse(s.isChase());
        assertTrue(s.atrsAboveEma50() < 0);
        assertTrue(s.pctAboveEma50() < 0);
    }

    @Test
    void theBandBoundariesAreInclusiveAtTheLowerEdge() {
        // exactly 2.5 ATR above (+25%) -> EXTENDED, exactly 4.0 ATR (+40%) -> CHASE
        assertEquals("EXTENDED", StretchFromMean.analyze(125, 100, 90, 10, 110).rating());
        assertEquals("CHASE",    StretchFromMean.analyze(140, 100, 90, 10, 110).rating());
        assertEquals("NORMAL",   StretchFromMean.analyze(124, 100, 90, 10, 110).rating());
    }

    /**
     * A very quiet series shrinks the ATR until an ordinary gap reads as many ATRs. This case is
     * taken from a real regression: 4.18 ATRs above the mean, but only 4.4% above it, which is not
     * a stock that has run away from anyone.
     */
    @Test
    void aLargeAtrDistanceOverATinyPercentageGapIsNotAChase() {
        StretchFromMean s = StretchFromMean.analyze(104.4, 100.0, 87.0, 1.053, 91.8);

        assertTrue(s.atrsAboveEma50() > 4.0, "the ATR reading alone would say CHASE");
        assertTrue(s.pctAboveEma50() < 6.0, "but price is barely off its mean");
        assertEquals("NORMAL", s.rating(), "both readings must agree before it counts as late");
    }

    @Test
    void aLargePercentageGapWithoutAtrDistanceIsAlsoNotAChase() {
        // +20% above the mean, but on a stock so volatile that is under 2 ATRs of travel.
        StretchFromMean s = StretchFromMean.analyze(120, 100, 90, 12, 100);

        assertTrue(s.pctAboveEma50() >= 10.0);
        assertTrue(s.atrsAboveEma50() < 2.5);
        assertEquals("NORMAL", s.rating(), "ordinary movement for this stock's own volatility");
    }

    /**
     * The reason distance is measured in ATRs: the same percentage means different things on a
     * quiet stock and a volatile one, and only the volatile one has actually gone far.
     */
    @Test
    void atrNormalisationSeparatesAQuietStockFromAVolatileOneAtTheSamePercentage() {
        StretchFromMean quiet    = StretchFromMean.analyze(115, 100, 90, 10, 105);  // 15% but 1.5 ATR
        StretchFromMean volatile_ = StretchFromMean.analyze(115, 100, 90, 2, 105);  // 15% but 7.5 ATR

        assertEquals(quiet.pctAboveEma50(), volatile_.pctAboveEma50(), 1e-9, "identical in percent");
        assertEquals("NORMAL", quiet.rating(), "15% on a wide-ranging stock is ordinary");
        assertEquals("CHASE", volatile_.rating(), "15% on a tight stock is a long way from home");
    }

    @Test
    void unusableInputsReportUnknownRatherThanNaNRatings() {
        assertFalse(StretchFromMean.analyze(100, 90, 80, 0, 95).isKnown(), "zero ATR");
        assertFalse(StretchFromMean.analyze(100, 90, 80, Double.NaN, 95).isKnown());
        assertFalse(StretchFromMean.analyze(Double.NaN, 90, 80, 3, 95).isKnown());
        assertFalse(StretchFromMean.analyze(100, 90, 80, 0, 95).toRow().containsKey("atrsAboveEma50"));
    }

    @Test
    void missingLongHistoryOmitsRunUpAndEma200WithoutBreakingTheRating() {
        StretchFromMean s = StretchFromMean.analyze(140, 100, Double.NaN, 10, Double.NaN);

        assertEquals("CHASE", s.rating(), "the EMA50 reading alone still rates it");
        assertTrue(Double.isNaN(s.runUp63d()));
        assertFalse(s.toRow().containsKey("runUp63dPct"), "absent readings are omitted, not zeroed");
        assertFalse(s.toRow().containsKey("pctAboveEma200"));
    }
}
