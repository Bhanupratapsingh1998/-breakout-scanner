package com.javawarriors.breakout.bullish;

import java.util.List;

/**
 * Swing structure - HH+HL, LH+LL, or MIXED - read off confirmed pivots.
 *
 * <p>Split out from the analyzers that need it (trend scoring, market regime, pattern detection)
 * so all three read structure the same way. The rule is deliberately the same one
 * {@code BreakoutAnalyzer} uses: compare the last two confirmed swing highs and the last two
 * confirmed swing lows, and require both to agree before calling a direction.
 */
public final class PriceStructure {

    private PriceStructure() {
    }

    /** Structure over the whole confirmed-pivot series. */
    public static String classify(IndicatorSnapshot s) {
        return classify(s.swingHighs, s.high, s.swingLows, s.low);
    }

    /**
     * Structure restricted to pivots at or after {@code fromIndex} - used when only the recent
     * base matters and the pivots from six months ago would drown it out.
     */
    public static String classifyFrom(IndicatorSnapshot s, int fromIndex) {
        return classify(s.swingHighs.stream().filter(i -> i >= fromIndex).toList(), s.high,
                s.swingLows.stream().filter(i -> i >= fromIndex).toList(), s.low);
    }

    private static String classify(List<Integer> highIdx, double[] high, List<Integer> lowIdx, double[] low) {
        if (highIdx.size() < 2 || lowIdx.size() < 2) return "MIXED";
        double h1 = high[highIdx.get(highIdx.size() - 2)];
        double h2 = high[highIdx.get(highIdx.size() - 1)];
        double l1 = low[lowIdx.get(lowIdx.size() - 2)];
        double l2 = low[lowIdx.get(lowIdx.size() - 1)];
        if (h2 > h1 && l2 > l1) return "HH+HL";
        if (h2 < h1 && l2 < l1) return "LH+LL";
        return "MIXED";
    }
}
