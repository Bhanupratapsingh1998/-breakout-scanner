package com.javawarriors.breakout.bullish;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Volume behaviour, worth 10 points.
 *
 * <p>Three separate questions, because a single volume reading answers none of them well:
 * <ul>
 *   <li><b>Accumulation (4).</b> Is the 20-day average above the 50-day average? A base being
 *       quietly bought shows a rising volume floor long before any one session looks unusual.</li>
 *   <li><b>Today's participation (3).</b> Latest session against the 20-day average. This is the
 *       one that confirms a breakout bar.</li>
 *   <li><b>Up/down volume split (3).</b> Volume on up days versus down days over the last 20
 *       sessions. This is what separates accumulation from distribution: two stocks can have
 *       identical average volume while one trades its volume into rallies and the other into
 *       declines.</li>
 * </ul>
 *
 * <p>A volume <em>contraction</em> inside a base is not penalised as hard as it might look: a
 * quiet, drying-up base with strong up/down split still scores respectably, which is the correct
 * reading of a coiled flat base.
 */
public final class VolumeAnalyzer {

    public static final double MAX_POINTS = 10;

    /** Sessions used for the up-day / down-day volume split. */
    private static final int SPLIT_WINDOW = 20;

    public record VolumeProfile(double avgVolume20, double avgVolume50, double currentRatio,
                                double accumulationRatio, double upDownVolumeRatio,
                                boolean expanding, String label, double points) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("avgVolume20", avgVolume20);
            row.put("avgVolume50", avgVolume50);
            row.put("currentRatio", currentRatio);
            row.put("accumulationRatio", accumulationRatio);
            row.put("upDownVolumeRatio", upDownVolumeRatio);
            row.put("expanding", expanding);
            return row;
        }
    }

    private VolumeAnalyzer() {
    }

    public static VolumeProfile analyze(IndicatorSnapshot s, BullishConfig cfg) {
        double accumulation = s.avgVolume50 == 0 ? Double.NaN : s.avgVolume20 / s.avgVolume50;
        double current = s.volumeRatio;
        double upDown = upDownVolumeRatio(s);

        double accumulationPoints;
        if (Double.isNaN(accumulation)) accumulationPoints = 2;
        else if (accumulation >= 1.3) accumulationPoints = 4;
        else if (accumulation >= 1.1) accumulationPoints = 3;
        else if (accumulation >= 0.95) accumulationPoints = 2;
        else if (accumulation >= 0.8) accumulationPoints = 1;
        else accumulationPoints = 0;

        double currentPoints;
        if (current >= 2.0) currentPoints = 3;
        else if (current >= cfg.getBreakoutVolumeRatio()) currentPoints = 2.5;
        else if (current >= 1.0) currentPoints = 1.5;
        else if (current >= 0.7) currentPoints = 0.5;
        else currentPoints = 0;

        double splitPoints;
        if (Double.isNaN(upDown)) splitPoints = 1.5;
        else if (upDown >= 1.5) splitPoints = 3;
        else if (upDown >= 1.15) splitPoints = 2.25;
        else if (upDown >= 0.9) splitPoints = 1.5;
        else if (upDown >= 0.7) splitPoints = 0.75;
        else splitPoints = 0;

        double points = accumulationPoints + currentPoints + splitPoints;
        boolean expanding = !Double.isNaN(accumulation) && accumulation >= 1.1;

        String label;
        if (!Double.isNaN(upDown) && upDown >= 1.5 && expanding) label = "ACCUMULATION";
        else if (current >= cfg.getBreakoutVolumeRatio()) label = "BREAKOUT VOLUME";
        else if (expanding) label = "EXPANDING";
        else if (!Double.isNaN(upDown) && upDown < 0.9) label = "DISTRIBUTION";
        else label = "CONTRACTING";

        return new VolumeProfile(s.avgVolume20, s.avgVolume50, current, accumulation, upDown,
                expanding, label, points);
    }

    /**
     * Total volume on up sessions divided by total volume on down sessions over the trailing
     * window. Returns NaN when there were no down sessions at all - dividing by zero would report
     * infinite accumulation off a technicality.
     */
    static double upDownVolumeRatio(IndicatorSnapshot s) {
        int from = Math.max(1, s.n - SPLIT_WINDOW);
        double up = 0, down = 0;
        for (int i = from; i < s.n; i++) {
            if (s.close[i] > s.close[i - 1]) up += s.volume[i];
            else if (s.close[i] < s.close[i - 1]) down += s.volume[i];
        }
        return down == 0 ? Double.NaN : up / down;
    }
}
