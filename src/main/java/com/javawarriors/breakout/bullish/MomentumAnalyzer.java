package com.javawarriors.breakout.bullish;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Momentum, worth 10 points - and deliberately not "whoever went up the most".
 *
 * <p>Section 23 of the spec is explicit that the engine must not simply surface stocks that have
 * already risen a lot, so raw return is worth very little here on its own: the return component
 * rewards <em>consistency</em> across 1M/3M/6M rather than magnitude, and RSI is scored as a band
 * with a peak in the 55-70 strength zone that falls away again above it. A stock at RSI 88 scores
 * worse than one at 62, which is the opposite of what a naive momentum screen would do.
 *
 * <p>ADX carries the rest: it says the move has trend structure rather than being a single gap.
 */
public final class MomentumAnalyzer {

    public static final double MAX_POINTS = 10;

    public record Momentum(double return1mPct, double return3mPct, double return6mPct,
                           double rsi, double adx, int positiveWindows, String label, double points) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("return1mPct", return1mPct);
            row.put("return3mPct", return3mPct);
            row.put("return6mPct", return6mPct);
            row.put("rsi", rsi);
            row.put("adx", adx);
            row.put("positiveWindows", positiveWindows);
            return row;
        }
    }

    private MomentumAnalyzer() {
    }

    public static Momentum analyze(IndicatorSnapshot s) {
        double r1 = pct(s.return1m);
        double r3 = pct(s.return3m);
        double r6 = pct(s.return6m);

        // RSI as a band, not a ladder. 55-70 is the strength zone; above 75 the reading is
        // information about how stretched the move is, not how strong it is.
        double rsi = s.lastRsi;
        double rsiPoints;
        if (rsi >= 55 && rsi <= 70) rsiPoints = 4;
        else if (rsi > 70 && rsi <= 75) rsiPoints = 3;
        else if (rsi >= 50 && rsi < 55) rsiPoints = 2.5;
        else if (rsi > 75 && rsi <= 82) rsiPoints = 1.5;
        else if (rsi >= 45 && rsi < 50) rsiPoints = 1;
        else rsiPoints = 0;   // below 45 is not momentum; above 82 is not a place to initiate

        double adx = s.lastAdx;
        double adxPoints;
        if (adx >= 30) adxPoints = 3;
        else if (adx >= 25) adxPoints = 2.5;
        else if (adx >= 20) adxPoints = 1.5;
        else adxPoints = 0;

        int positive = 0;
        if (!Double.isNaN(r1) && r1 > 0) positive++;
        if (!Double.isNaN(r3) && r3 > 0) positive++;
        if (!Double.isNaN(r6) && r6 > 0) positive++;
        double consistencyPoints = positive;   // 0-3, one point per window that is up

        double points = rsiPoints + adxPoints + consistencyPoints;

        String label;
        if (rsi > 82) label = "OVERHEATED";
        else if (positive == 3 && adx >= 25 && rsi >= 55) label = "STRONG";
        else if (positive >= 2 && rsi >= 50) label = "HEALTHY";
        else if (positive >= 1) label = "DEVELOPING";
        else label = "WEAK";

        return new Momentum(r1, r3, r6, rsi, adx, positive, label, points);
    }

    private static double pct(double fraction) {
        return Double.isNaN(fraction) ? Double.NaN : fraction * 100;
    }
}
