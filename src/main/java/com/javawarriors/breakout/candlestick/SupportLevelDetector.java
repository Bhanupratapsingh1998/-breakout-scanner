package com.javawarriors.breakout.candlestick;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds a "major" support level near a given price, from swing-low pivots, EMA50, and EMA200 —
 * deliberately not just "the nearest candle low," which would treat every minor dip as support.
 * A pivot cluster only qualifies as major support when it has evidence: multiple historical
 * touches, or it's the single most significant swing low in the lookback window.
 */
public class SupportLevelDetector {

    public record SupportResult(double level, double distancePct, int touches, String evidence) {}

    private static final int LOOKBACK = 120;
    private static final int PIVOT_WINDOW = 3;

    private SupportLevelDetector() {}

    private static List<Integer> swingLowIndices(List<Bar> bars, int from, int to, int window) {
        List<Integer> out = new ArrayList<>();
        for (int i = Math.max(from, window); i < to - window; i++) {
            double li = bars.get(i).low();
            boolean isLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && bars.get(j).low() <= li) { isLow = false; break; }
            }
            if (isLow) out.add(i);
        }
        return out;
    }

    /**
     * Nearest qualifying support to `referenceLow`, or null if nothing qualifies within the
     * lookback. `uptoIndexExclusive` should be the pattern's own bar index (or later), so the
     * pattern's own low doesn't get counted as evidence for itself.
     */
    public static SupportResult nearestSupport(List<Bar> bars, int uptoIndexExclusive, double referenceLow,
                                                double tolerancePct, Double ema50, Double ema200) {
        int to = Math.min(uptoIndexExclusive, bars.size());
        int from = Math.max(0, to - LOOKBACK);
        List<Integer> pivots = swingLowIndices(bars, from, to, PIVOT_WINDOW);

        double overallLow = Double.MAX_VALUE;
        for (int idx : pivots) overallLow = Math.min(overallLow, bars.get(idx).low());

        List<double[]> clusters = new ArrayList<>(); // {level, touches}
        boolean[] used = new boolean[pivots.size()];
        for (int a = 0; a < pivots.size(); a++) {
            if (used[a]) continue;
            double base = bars.get(pivots.get(a)).low();
            int touches = 1;
            double sum = base;
            used[a] = true;
            for (int b = a + 1; b < pivots.size(); b++) {
                if (used[b]) continue;
                double v = bars.get(pivots.get(b)).low();
                if (base > 0 && Math.abs(v - base) / base * 100 <= tolerancePct) {
                    used[b] = true;
                    touches++;
                    sum += v;
                }
            }
            clusters.add(new double[] { sum / touches, touches });
        }

        SupportResult best = null;
        for (double[] cluster : clusters) {
            double level = cluster[0];
            int touches = (int) cluster[1];
            boolean qualifies = touches >= 2 || level == overallLow;
            if (!qualifies || level <= 0) continue;
            double distancePct = Math.abs(referenceLow - level) / level * 100;
            String evidence = touches >= 2 ? touches + " historical touches" : "significant swing low";
            if (best == null || distancePct < best.distancePct()) {
                best = new SupportResult(level, distancePct, touches, evidence);
            }
        }
        best = considerEma(best, ema50, referenceLow, "EMA50 reaction");
        best = considerEma(best, ema200, referenceLow, "EMA200 reaction");
        return best;
    }

    private static SupportResult considerEma(SupportResult best, Double ema, double referenceLow, String evidence) {
        if (ema == null || ema <= 0) return best;
        double distancePct = Math.abs(referenceLow - ema) / ema * 100;
        if (best == null || distancePct < best.distancePct()) {
            return new SupportResult(ema, distancePct, 0, evidence);
        }
        return best;
    }
}
