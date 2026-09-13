package com.javawarriors.breakout.intraday;

import com.javawarriors.breakout.intraday.CandlePatternDetector.CandlePattern;
import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CandlePatternDetectorTest {

    private static long t = 1_700_000_000L;

    private static Bar bar(double o, double h, double l, double c) {
        return new Bar(t += 900, o, h, l, c, 100_000);
    }

    /** Five candles trending the given way, so a wick pattern has a context to be judged against. */
    private static List<Bar> context(boolean up, double start) {
        List<Bar> bars = new ArrayList<>();
        double p = start;
        for (int i = 0; i < 6; i++) {
            double next = up ? p * 1.004 : p * 0.996;
            bars.add(bar(p, Math.max(p, next) * 1.001, Math.min(p, next) * 0.999, next));
            p = next;
        }
        return bars;
    }

    private static boolean has(List<CandlePattern> ps, String type) {
        return ps.stream().anyMatch(p -> p.type().equals(type));
    }

    @Test
    void aLongUpperWickAfterARallyIsAShootingStar() {
        List<Bar> bars = context(true, 100);
        // small body near the low, long upper wick
        bars.add(bar(104.0, 107.0, 103.9, 104.2));

        List<CandlePattern> ps = CandlePatternDetector.detectAt(bars, bars.size() - 1);

        assertTrue(has(ps, "SHOOTING_STAR"), "got " + ps.stream().map(CandlePattern::type).toList());
        assertFalse(has(ps, "INVERTED_HAMMER"), "the same shape after a rally is not bullish");
    }

    @Test
    void theSameShapeAfterADeclineIsAnInvertedHammerInstead() {
        List<Bar> bars = context(false, 100);
        bars.add(bar(97.0, 100.0, 96.9, 97.2));

        List<CandlePattern> ps = CandlePatternDetector.detectAt(bars, bars.size() - 1);

        assertTrue(has(ps, "INVERTED_HAMMER"), "got " + ps.stream().map(CandlePattern::type).toList());
        assertFalse(has(ps, "SHOOTING_STAR"), "context, not shape, decides the direction");
    }

    @Test
    void aLongLowerWickIsAHammerAfterADeclineAndAHangingManAfterARally() {
        List<Bar> down = context(false, 100);
        down.add(bar(97.2, 97.3, 94.0, 97.0));
        assertTrue(has(CandlePatternDetector.detectAt(down, down.size() - 1), "HAMMER"));

        List<Bar> up = context(true, 100);
        up.add(bar(104.2, 104.3, 101.0, 104.0));
        List<CandlePattern> ps = CandlePatternDetector.detectAt(up, up.size() - 1);
        assertTrue(has(ps, "HANGING_MAN"));
        assertFalse(has(ps, "HAMMER"));
    }

    @Test
    void engulfingRequiresTheBodyToActuallySwallowThePriorOne() {
        List<Bar> bars = context(true, 100);
        bars.add(bar(104, 104.2, 102.0, 102.2));      // down candle
        bars.add(bar(102.0, 105.0, 101.9, 104.6));    // up candle engulfing it
        assertTrue(has(CandlePatternDetector.detectAt(bars, bars.size() - 1), "BULLISH_ENGULFING"));

        List<Bar> small = context(true, 100);
        small.add(bar(104, 104.2, 102.0, 102.2));
        small.add(bar(102.5, 103.2, 102.4, 103.0));   // inside the prior body, not engulfing
        assertFalse(has(CandlePatternDetector.detectAt(small, small.size() - 1), "BULLISH_ENGULFING"));
    }

    @Test
    void threeFallingBodiesAreThreeBlackCrows() {
        List<Bar> bars = context(true, 100);
        bars.add(bar(104.0, 104.1, 102.0, 102.2));
        bars.add(bar(102.2, 102.3, 100.2, 100.4));
        bars.add(bar(100.4, 100.5, 98.4, 98.6));

        assertTrue(has(CandlePatternDetector.detectAt(bars, bars.size() - 1), "THREE_BLACK_CROWS"));
    }

    @Test
    void anAlmostAllBodyCandleIsAMarubozu() {
        List<Bar> up = context(false, 100);
        up.add(bar(97.0, 100.05, 96.98, 100.0));
        assertTrue(has(CandlePatternDetector.detectAt(up, up.size() - 1), "BULLISH_MARUBOZU"));

        List<Bar> down = context(true, 100);
        down.add(bar(104.0, 104.02, 101.0, 101.05));
        assertTrue(has(CandlePatternDetector.detectAt(down, down.size() - 1), "BEARISH_MARUBOZU"));
    }

    @Test
    void aFeaturelessCandleMatchesNothing() {
        List<Bar> bars = context(true, 100);
        // balanced body with even wicks on both sides - no pattern should claim it
        bars.add(bar(104.0, 104.6, 103.4, 104.3));

        List<CandlePattern> ps = CandlePatternDetector.detectAt(bars, bars.size() - 1);
        assertFalse(has(ps, "SHOOTING_STAR"));
        assertFalse(has(ps, "HAMMER"));
        assertFalse(has(ps, "DRAGONFLY_DOJI"));
        assertFalse(has(ps, "GRAVESTONE_DOJI"));
    }

    @Test
    void strongestPicksTheHighestStrengthOfTheAskedDirection() {
        List<Bar> bars = context(true, 100);
        bars.add(bar(104.0, 107.0, 103.9, 104.2));

        CandlePattern bear = CandlePatternDetector.strongest(bars, bars.size() - 1, Direction.BEARISH);
        assertNotNull(bear);
        assertEquals(Direction.BEARISH, bear.direction());
        assertNull(CandlePatternDetector.strongest(bars, bars.size() - 1, Direction.BULLISH),
                "no bullish pattern exists on this candle");
    }

    @Test
    void theDropdownVocabularyCoversBothDirections() {
        assertEquals(9, CandlePatternDetector.BULLISH_TYPES.size());
        assertEquals(9, CandlePatternDetector.BEARISH_TYPES.size());
        assertTrue(CandlePatternDetector.BEARISH_TYPES.containsKey("SHOOTING_STAR"),
                "the strategy's headline pattern must be filterable");
        assertTrue(CandlePatternDetector.BULLISH_TYPES.containsKey("HAMMER"));
    }

    @Test
    void everyDetectedPatternReportsItselfCompletely() {
        List<Bar> bars = context(true, 100);
        bars.add(bar(104.0, 107.0, 103.9, 104.2));

        for (CandlePattern p : CandlePatternDetector.detectAt(bars, bars.size() - 1)) {
            assertFalse(p.type().isBlank());
            assertFalse(p.name().isBlank());
            assertFalse(p.description().isBlank());
            assertTrue(p.strength() >= 1 && p.strength() <= 3, p.type() + " strength " + p.strength());
            assertNotNull(p.direction());
        }
    }
}
