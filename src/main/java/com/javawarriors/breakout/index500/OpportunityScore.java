package com.javawarriors.breakout.index500;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The 0-100 opportunity score, carried as its components rather than as one number.
 *
 * <p>Weights are exactly the spec's: 6M decline 15, reversal pattern 20, price structure 15, volume
 * 10, RSI 10, ADX 10, EMA structure 10, relative strength 10.
 *
 * <p>Every component travels to the API so the dashboard can show why a stock scored what it did.
 * A score nobody can decompose is a number people either trust blindly or ignore, and neither is
 * useful.
 */
public record OpportunityScore(double decline, double pattern, double structure, double volume,
                               double rsi, double adx, double ema, double relativeStrength,
                               double total) {

    public static final double MAX_DECLINE = 15;
    public static final double MAX_PATTERN = 20;
    public static final double MAX_STRUCTURE = 15;
    public static final double MAX_VOLUME = 10;
    public static final double MAX_RSI = 10;
    public static final double MAX_ADX = 10;
    public static final double MAX_EMA = 10;
    public static final double MAX_RELATIVE_STRENGTH = 10;
    public static final double MAX_TOTAL = 100;

    public static OpportunityScore zero() {
        return new OpportunityScore(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static OpportunityScore of(double decline, double pattern, double structure, double volume,
                                      double rsi, double adx, double ema, double relativeStrength) {
        double total = decline + pattern + structure + volume + rsi + adx + ema + relativeStrength;
        return new OpportunityScore(round(decline), round(pattern), round(structure), round(volume),
                round(rsi), round(adx), round(ema), round(relativeStrength),
                round(Math.max(0, Math.min(MAX_TOTAL, total))));
    }

    public Map<String, Object> toRow() {
        Map<String, Object> components = new LinkedHashMap<>();
        components.put("decline", component(decline, MAX_DECLINE));
        components.put("pattern", component(pattern, MAX_PATTERN));
        components.put("structure", component(structure, MAX_STRUCTURE));
        components.put("volume", component(volume, MAX_VOLUME));
        components.put("rsi", component(rsi, MAX_RSI));
        components.put("adx", component(adx, MAX_ADX));
        components.put("ema", component(ema, MAX_EMA));
        components.put("relativeStrength", component(relativeStrength, MAX_RELATIVE_STRENGTH));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("total", total);
        row.put("maxScore", MAX_TOTAL);
        row.put("components", components);
        return row;
    }

    private static Map<String, Object> component(double points, double max) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("points", points);
        m.put("maxPoints", max);
        return m;
    }

    private static double round(double v) {
        return Double.isNaN(v) ? 0 : Math.round(v * 10.0) / 10.0;
    }
}
