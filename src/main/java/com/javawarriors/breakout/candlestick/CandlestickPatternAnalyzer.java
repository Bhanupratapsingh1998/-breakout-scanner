package com.javawarriors.breakout.candlestick;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Reusable, pure candlestick pattern detection — deliberately independent of BreakoutAnalyzer
 * and ReversalAnalyzer so it can be layered onto either without touching their tested logic.
 * Add new pattern types here as additional `detectX(bars, i)` methods and wire them into
 * {@link #detectRecent}.
 */
public class CandlestickPatternAnalyzer {

    private static final double HAMMER_MAX_BODY_PCT_OF_RANGE = 0.4;
    private static final double HAMMER_MIN_LOWER_WICK_MULT = 2.0;
    private static final double HAMMER_MAX_UPPER_WICK_MULT = 0.5;
    private static final int NEAR_LOW_LOOKBACK = 10;
    private static final double NEAR_LOW_TOLERANCE = 1.02;
    private static final int DECLINE_LOOKBACK = 10;
    private static final double MEANINGFUL_DECLINE_PCT = 0.05;
    private static final double STAR_BODY1_MIN_PCT_OF_RANGE = 0.5;
    private static final double STAR_BODY2_MAX_PCT_OF_RANGE = 0.3;
    private static final double STAR_BODY3_MIN_PCT_OF_RANGE = 0.5;

    private CandlestickPatternAnalyzer() {}

    private static boolean isAfterMeaningfulDecline(List<Bar> bars, int i) {
        int from = Math.max(0, i - DECLINE_LOOKBACK);
        if (from >= i) return false;
        double startClose = bars.get(from).close();
        double patternLow = bars.get(i).low();
        return startClose > 0 && (startClose - patternLow) / startClose >= MEANINGFUL_DECLINE_PCT;
    }

    private static boolean isNearRecentLow(List<Bar> bars, int i, int lookback) {
        int from = Math.max(0, i - lookback);
        if (from >= i) return true;
        double minLow = Double.MAX_VALUE;
        for (int j = from; j < i; j++) minLow = Math.min(minLow, bars.get(j).low());
        return bars.get(i).low() <= minLow * NEAR_LOW_TOLERANCE;
    }

    /** Small body near the top of the range, a long lower wick, little/no upper wick, after a
     *  decline and at a fresh local low — the classic capitulation-reversal candle. */
    public static CandlestickPattern detectHammer(List<Bar> bars, int i) {
        if (i < 0 || i >= bars.size()) return null;
        Bar b = bars.get(i);
        double range = b.high() - b.low();
        if (range <= 0) return null;
        double body = Math.abs(b.close() - b.open());
        double lowerWick = Math.min(b.open(), b.close()) - b.low();
        double upperWick = b.high() - Math.max(b.open(), b.close());
        if (body > HAMMER_MAX_BODY_PCT_OF_RANGE * range) return null;
        if (lowerWick < HAMMER_MIN_LOWER_WICK_MULT * body) return null;
        if (upperWick > HAMMER_MAX_UPPER_WICK_MULT * Math.max(body, range * 0.05)) return null;
        if (!isAfterMeaningfulDecline(bars, i)) return null;
        if (!isNearRecentLow(bars, i, NEAR_LOW_LOOKBACK)) return null;

        double strength = Math.min(1.0, lowerWick / range);
        return new CandlestickPattern("HAMMER", i, i, b.time(), b.high(), b.low(), strength,
                "Small body with a long lower wick after a decline, near a fresh low");
    }

    /** A bullish real body that fully engulfs the prior bearish real body (bodies only — wicks
     *  are ignored, per the standard definition). */
    public static CandlestickPattern detectBullishEngulfing(List<Bar> bars, int i) {
        if (i < 1 || i >= bars.size()) return null;
        Bar prev = bars.get(i - 1);
        Bar curr = bars.get(i);
        boolean prevBearish = prev.close() < prev.open();
        boolean currBullish = curr.close() > curr.open();
        if (!prevBearish || !currBullish) return null;
        boolean engulfsBody = curr.open() <= prev.close() && curr.close() >= prev.open();
        if (!engulfsBody) return null;

        double prevBody = prev.open() - prev.close();
        double currBody = curr.close() - curr.open();
        double strength = prevBody > 0 ? Math.min(1.0, currBody / prevBody) : 1.0;
        double high = Math.max(prev.high(), curr.high());
        double low = Math.min(prev.low(), curr.low());
        return new CandlestickPattern("BULLISH_ENGULFING", i - 1, i, curr.time(), high, low, strength,
                "Bullish real body fully engulfs the prior bearish real body");
    }

    /** Three-candle reversal: a strong bearish candle, an indecision candle, then a strong
     *  bullish candle that recovers at least half of the first candle's body. */
    public static CandlestickPattern detectMorningStar(List<Bar> bars, int i) {
        if (i < 2 || i >= bars.size()) return null;
        Bar c1 = bars.get(i - 2);
        Bar c2 = bars.get(i - 1);
        Bar c3 = bars.get(i);

        double range1 = c1.high() - c1.low();
        double body1 = c1.open() - c1.close();
        boolean c1Bearish = c1.close() < c1.open();
        if (!c1Bearish || range1 <= 0 || body1 < STAR_BODY1_MIN_PCT_OF_RANGE * range1) return null;

        double range2 = c2.high() - c2.low();
        double body2 = Math.abs(c2.close() - c2.open());
        if (range2 <= 0 || body2 > STAR_BODY2_MAX_PCT_OF_RANGE * range2) return null;

        double range3 = c3.high() - c3.low();
        double body3 = c3.close() - c3.open();
        boolean c3Bullish = c3.close() > c3.open();
        if (!c3Bullish || range3 <= 0 || body3 < STAR_BODY3_MIN_PCT_OF_RANGE * range3) return null;

        double midpoint1 = (c1.open() + c1.close()) / 2;
        if (c3.close() < midpoint1) return null; // insufficient recovery into candle 1's body

        if (!isAfterMeaningfulDecline(bars, i - 2)) return null;

        double high = Math.max(c1.high(), Math.max(c2.high(), c3.high()));
        double low = Math.min(c1.low(), Math.min(c2.low(), c3.low()));
        double recoveryPct = body1 > 0 ? (c3.close() - midpoint1) / body1 : 1.0;
        return new CandlestickPattern("MORNING_STAR", i - 2, i, c3.time(), high, low,
                Math.max(0, Math.min(1.0, 0.5 + recoveryPct)),
                "Bearish candle, then indecision, then a strong bullish recovery candle");
    }

    /**
     * All non-overlapping patterns detected across the trailing `lookback` bars, in chronological
     * order. When two candidates' candle ranges overlap (they describe some of the same candles),
     * only the higher-ranked pattern (Morning Star > Bullish Engulfing > Hammer) survives — see
     * {@link CandlestickPattern#rank()}. Distinct patterns at non-overlapping candles can both
     * appear, and both contribute to the confirmation score.
     */
    public static List<CandlestickPattern> detectRecent(List<Bar> bars, int lookback) {
        int n = bars.size();
        List<CandlestickPattern> candidates = new ArrayList<>();
        int from = Math.max(2, n - lookback);
        for (int i = from; i < n; i++) {
            CandlestickPattern hammer = detectHammer(bars, i);
            if (hammer != null) candidates.add(hammer);
            CandlestickPattern engulfing = detectBullishEngulfing(bars, i);
            if (engulfing != null) candidates.add(engulfing);
            CandlestickPattern star = detectMorningStar(bars, i);
            if (star != null) candidates.add(star);
        }
        candidates.sort((a, b) -> {
            int r = b.rank() - a.rank();
            return r != 0 ? r : b.endIndex() - a.endIndex();
        });

        List<CandlestickPattern> accepted = new ArrayList<>();
        for (CandlestickPattern c : candidates) {
            boolean overlaps = accepted.stream().anyMatch(a -> rangesOverlap(a, c));
            if (!overlaps) accepted.add(c);
        }
        accepted.sort((a, b) -> a.endIndex() - b.endIndex());
        return accepted;
    }

    private static boolean rangesOverlap(CandlestickPattern a, CandlestickPattern b) {
        return a.startIndex() <= b.endIndex() && b.startIndex() <= a.endIndex();
    }
}
