package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.timeframe.TrendAlignment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Trend quality, worth 20 of the 100 points - the largest single component, because everything
 * else in a long setup is a bet that the existing trend continues.
 *
 * <p>Four things are measured, and they are deliberately not redundant:
 * <ul>
 *   <li><b>Stack alignment (8).</b> Price &gt; EMA20 &gt; EMA50 &gt; EMA200 is the textbook ideal.
 *       Partial credit is steeply discounted rather than linear: two of three relations is a trend
 *       in transition, not two-thirds of a trend.</li>
 *   <li><b>Long-term regime (3).</b> Above the 200 EMA at all. Separate from the stack because a
 *       stock can be neatly stacked inside a bear market bounce and still be below its 200 EMA.</li>
 *   <li><b>Structure (5).</b> HH+HL. EMAs are lagging averages; swing structure is what price
 *       actually did, and the two disagree exactly when a trend is turning.</li>
 *   <li><b>Slope (4).</b> The 50 and 200 EMAs rising. A flat stack is a range, not a trend, and
 *       the stack test alone cannot tell the difference.</li>
 * </ul>
 */
public final class BullishTrendAnalyzer {

    public static final double MAX_POINTS = 20;

    /** Sessions over which EMA slope is measured - long enough that one gap does not set it. */
    private static final int SLOPE_LOOKBACK = 20;

    public record Trend(boolean priceAboveEma20, boolean ema20AboveEma50, boolean ema50AboveEma200,
                        boolean priceAboveEma200, String structure, boolean ema50Rising,
                        boolean ema200Rising, double distanceFromEma20Pct, double distanceFromEma50Pct,
                        double distanceFromEma200Pct, String label, double points) {

        /** All three stack relations hold - the condition the spec asks to prefer. */
        public boolean fullyStacked() {
            return priceAboveEma20 && ema20AboveEma50 && ema50AboveEma200;
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("fullyStacked", fullyStacked());
            row.put("priceAboveEma20", priceAboveEma20);
            row.put("ema20AboveEma50", ema20AboveEma50);
            row.put("ema50AboveEma200", ema50AboveEma200);
            row.put("priceAboveEma200", priceAboveEma200);
            row.put("structure", structure);
            row.put("ema50Rising", ema50Rising);
            row.put("ema200Rising", ema200Rising);
            row.put("distanceFromEma20Pct", distanceFromEma20Pct);
            row.put("distanceFromEma50Pct", distanceFromEma50Pct);
            row.put("distanceFromEma200Pct", distanceFromEma200Pct);
            return row;
        }
    }

    private BullishTrendAnalyzer() {
    }

    public static Trend analyze(IndicatorSnapshot s) {
        boolean aboveEma20 = s.price > s.lastEma20;
        boolean ema20Above50 = s.lastEma20 > s.lastEma50;
        boolean ema50Above200 = s.lastEma50 > s.lastEma200;
        boolean aboveEma200 = s.price > s.lastEma200;
        String structure = PriceStructure.classify(s);

        double slope50 = IndicatorSnapshot.slopePct(s.ema50, SLOPE_LOOKBACK);
        double slope200 = IndicatorSnapshot.slopePct(s.ema200, SLOPE_LOOKBACK);
        boolean rising50 = !Double.isNaN(slope50) && slope50 > 0;
        boolean rising200 = !Double.isNaN(slope200) && slope200 > 0;

        int stackCount = (aboveEma20 ? 1 : 0) + (ema20Above50 ? 1 : 0) + (ema50Above200 ? 1 : 0);
        double stackPoints = switch (stackCount) {
            case 3 -> 8;
            case 2 -> 5;
            case 1 -> 2;
            default -> 0;
        };
        double regimePoints = aboveEma200 ? 3 : 0;
        double structurePoints = switch (structure) {
            case "HH+HL" -> 5;
            case "LH+LL" -> 0;
            default -> 2;
        };
        double slopePoints = (rising50 ? 2 : 0) + (rising200 ? 2 : 0);

        double points = stackPoints + regimePoints + structurePoints + slopePoints;

        String label;
        if (stackCount == 3 && "HH+HL".equals(structure)) label = "STRONG UPTREND";
        else if (stackCount == 3) label = "UPTREND";
        else if (stackCount >= 2 && aboveEma200) label = "DEVELOPING UPTREND";
        else if (stackCount <= 1 && !aboveEma200) label = "DOWNTREND";
        else label = "MIXED";

        return new Trend(aboveEma20, ema20Above50, ema50Above200, aboveEma200, structure,
                rising50, rising200,
                pctFrom(s.price, s.lastEma20), pctFrom(s.price, s.lastEma50), pctFrom(s.price, s.lastEma200),
                label, points);
    }

    /**
     * Higher-timeframe agreement, reused wholesale from the existing {@link TrendAlignment} rather
     * than re-derived - a daily breakout inside a falling weekly trend is the same bull trap here
     * as it is in the breakout scanner, and both tabs should read it identically.
     */
    public static TrendAlignment.Alignment higherTimeframes(IndicatorSnapshot s) {
        return TrendAlignment.analyze(s.bars);
    }

    private static double pctFrom(double price, double level) {
        return level <= 0 ? Double.NaN : (price / level - 1) * 100;
    }
}
