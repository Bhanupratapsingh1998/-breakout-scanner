package com.javawarriors.breakout.breakout;

import com.javawarriors.breakout.model.Bar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Judges the breakout candle itself, which the 10-point checklist otherwise never looks at.
 *
 * <p>The existing gates establish that price closed above resistance on above-average volume, but
 * two bars can satisfy both and mean opposite things: a wide-bodied bar closing on its high is
 * buyers taking control, while a bar that tags a new high and closes back near its open is a
 * failed push that the volume gate happily waves through.
 *
 * <p>Deliberately NOT built on the named patterns in {@code CandlestickPatternAnalyzer}. Hammer,
 * bullish engulfing and morning star are reversal patterns that detect capitulation lows; a hammer
 * printed on breakout day is a long lower wick, i.e. price being driven back down intraday, which
 * is the opposite of confirmation. What a breakout wants is measured bar structure, not a pattern
 * name.
 *
 * <p>Four independent readings, one point each:
 * <ul>
 *   <li>close in the top quarter of the range — buyers held the gain into the close</li>
 *   <li>real body at least half the range — direction, not indecision</li>
 *   <li>range at or above ATR — an expansion bar, not a drift through the level</li>
 *   <li>upper wick no more than a quarter of the range — no visible rejection</li>
 * </ul>
 * A dominant upper wick vetoes the rating outright regardless of score: it is the single clearest
 * tell of a breakout that has already been sold into.
 */
public record BreakoutBarQuality(String rating, int score, double closePosition, double bodyRatio,
                                 double rangeVsAtr, double upperWickRatio) {

    public static final int MAX_SCORE = 4;

    private static final double CLOSE_POSITION_MIN = 0.75;
    private static final double BODY_RATIO_MIN = 0.50;
    private static final double RANGE_VS_ATR_MIN = 1.00;
    private static final double UPPER_WICK_MAX = 0.25;

    /** An upper wick this large means the high was rejected, whatever the other three readings say. */
    private static final double UPPER_WICK_VETO = 0.40;

    public static BreakoutBarQuality unknown() {
        return new BreakoutBarQuality("UNKNOWN", 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public boolean isKnown() {
        return !"UNKNOWN".equals(rating);
    }

    /** True when the candle argues the breakout was real. */
    public boolean confirms() {
        return "STRONG".equals(rating);
    }

    /**
     * @param bars          full daily series
     * @param breakoutIndex index of the confirmed breakout bar, or negative if none
     * @param atrValue      ATR at that bar; NaN or non-positive disables the expansion reading
     */
    public static BreakoutBarQuality analyze(List<Bar> bars, int breakoutIndex, double atrValue) {
        if (bars == null || breakoutIndex < 0 || breakoutIndex >= bars.size()) return unknown();

        Bar b = bars.get(breakoutIndex);
        double range = b.high() - b.low();
        // A zero-range bar (circuit-locked, or a data glitch) has no structure to read. Returning
        // UNKNOWN rather than dividing by zero keeps NaN out of every downstream ratio.
        if (!(range > 0)) return unknown();

        double bodyTop = Math.max(b.open(), b.close());
        double closePosition = (b.close() - b.low()) / range;
        double bodyRatio = Math.abs(b.close() - b.open()) / range;
        double upperWickRatio = (b.high() - bodyTop) / range;
        boolean atrUsable = !Double.isNaN(atrValue) && atrValue > 0;
        double rangeVsAtr = atrUsable ? range / atrValue : Double.NaN;

        int score = 0;
        if (closePosition >= CLOSE_POSITION_MIN) score++;
        if (bodyRatio >= BODY_RATIO_MIN) score++;
        if (atrUsable && rangeVsAtr >= RANGE_VS_ATR_MIN) score++;
        if (upperWickRatio <= UPPER_WICK_MAX) score++;

        // A bearish breakout bar closes below its open: price cleared resistance intraday and gave
        // it all back. Never STRONG, whatever the wick and range readings look like.
        boolean bearishBody = b.close() < b.open();
        String rating;
        if (upperWickRatio >= UPPER_WICK_VETO || bearishBody) rating = "WEAK";
        else if (score >= 3) rating = "STRONG";
        else if (score == 2) rating = "FAIR";
        else rating = "WEAK";

        return new BreakoutBarQuality(rating, score, closePosition, bodyRatio, rangeVsAtr, upperWickRatio);
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rating", rating);
        row.put("score", score);
        row.put("maxScore", MAX_SCORE);
        if (isKnown()) {
            row.put("closePositionPct", closePosition * 100);
            row.put("bodyRatioPct", bodyRatio * 100);
            row.put("upperWickPct", upperWickRatio * 100);
            if (!Double.isNaN(rangeVsAtr)) row.put("rangeVsAtr", rangeVsAtr);
        }
        return row;
    }
}
