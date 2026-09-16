package com.javawarriors.breakout.index500.pattern;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one detector found on one stock.
 *
 * <p>Every detector returns this same shape, so the filter, the table and the score never need to
 * know which detector produced a row. A detector that found nothing returns {@link #absent} rather
 * than null, so callers never branch on nullability.
 *
 * @param confidence   0-10, where 10 is "every corroborating test this detector knows also passed"
 * @param confirmation what still has to happen before the pattern means anything - see
 *                     {@link #WAIT_FOR_BREAKOUT} and friends
 */
public record PatternResult(String symbol, String patternType, String patternName, boolean detected,
                            double confidence, Long formationDate, double support, double resistance,
                            double breakoutLevel, double currentPrice, double target, double stopLoss,
                            double riskReward, String confirmation, String explanation) {

    public static final String CONFIRMED = "CONFIRMED";
    public static final String WAIT_FOR_BREAKOUT = "WAIT FOR BREAKOUT";
    public static final String WAIT_FOR_CONFIRMATION = "WAIT FOR CONFIRMATION";
    public static final String FORMING = "FORMING";

    public static PatternResult absent(String symbol, String type, String name) {
        return new PatternResult(symbol, type, name, false, 0, null, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, null, null);
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patternType", patternType);
        row.put("patternName", patternName);
        row.put("detected", detected);
        row.put("confidence", confidence);
        if (!detected) return row;
        row.put("formationDate", formationDate);
        putIfKnown(row, "support", support);
        putIfKnown(row, "resistance", resistance);
        putIfKnown(row, "breakoutLevel", breakoutLevel);
        putIfKnown(row, "currentPrice", currentPrice);
        putIfKnown(row, "target", target);
        putIfKnown(row, "stopLoss", stopLoss);
        putIfKnown(row, "riskReward", riskReward);
        row.put("confirmation", confirmation);
        row.put("explanation", explanation);
        return row;
    }

    private static void putIfKnown(Map<String, Object> row, String key, double value) {
        if (!Double.isNaN(value) && !Double.isInfinite(value)) row.put(key, value);
    }
}
