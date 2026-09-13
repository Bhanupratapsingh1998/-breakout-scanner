package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.model.Bar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Relative strength against the Nifty 50 and the Nifty 500, worth 15 points.
 *
 * <p>This is the component that separates a stock genuinely being accumulated from one that merely
 * floated up with the whole market. A 12% three-month return means one thing when the index did
 * 2% and something else entirely when the index did 14% - and only the excess is evidence about
 * the stock.
 *
 * <p>The Nifty 500 carries most of the weight (11 of 15) because it is the actual universe being
 * ranked; beating the broad market is the relevant bar. The Nifty 50 contributes the remaining 4
 * as a large-cap yardstick, which is what tells a midcap apart from a large cap moving in step
 * with the index it belongs to.
 *
 * <p>Windows are weighted 3M &gt; 1M &gt; 6M: one month is noise-prone, six months is largely
 * historical, three is the horizon a swing setup is actually riding.
 */
public final class RelativeStrengthAnalyzer {

    public static final double MAX_POINTS = 15;

    /**
     * @param excess1mPct stock return minus Nifty 500 return over 21 sessions, in percentage points
     */
    public record RelativeStrength(double excess1mPct, double excess3mPct, double excess6mPct,
                                   double excessVsNifty50_3mPct,
                                   double bench1mPct, double bench3mPct, double bench6mPct,
                                   boolean outperformingBroadMarket, String label, double points) {

        public static RelativeStrength unknown() {
            return new RelativeStrength(Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, false, "UNKNOWN", 0);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("excess1mPct", excess1mPct);
            row.put("excess3mPct", excess3mPct);
            row.put("excess6mPct", excess6mPct);
            row.put("excessVsNifty50_3mPct", excessVsNifty50_3mPct);
            row.put("benchmark1mPct", bench1mPct);
            row.put("benchmark3mPct", bench3mPct);
            row.put("benchmark6mPct", bench6mPct);
            row.put("outperformingBroadMarket", outperformingBroadMarket);
            return row;
        }
    }

    private RelativeStrengthAnalyzer() {
    }

    public static RelativeStrength analyze(IndicatorSnapshot s, List<Bar> nifty50, List<Bar> nifty500) {
        double[] broad = closes(nifty500);
        double[] large = closes(nifty50);
        if (broad == null && large == null) return RelativeStrength.unknown();

        // Fall back to whichever index is available so a single failed index fetch degrades the
        // reading rather than blanking it.
        double[] primary = broad != null ? broad : large;

        double bench1 = pct(BreakoutAnalyzer.trailingReturn(primary, IndicatorSnapshot.BARS_1M));
        double bench3 = pct(BreakoutAnalyzer.trailingReturn(primary, IndicatorSnapshot.BARS_3M));
        double bench6 = pct(BreakoutAnalyzer.trailingReturn(primary, IndicatorSnapshot.BARS_6M));

        double e1 = excess(pct(s.return1m), bench1);
        double e3 = excess(pct(s.return3m), bench3);
        double e6 = excess(pct(s.return6m), bench6);

        double e3vs50 = large == null ? Double.NaN
                : excess(pct(s.return3m), pct(BreakoutAnalyzer.trailingReturn(large, IndicatorSnapshot.BARS_3M)));

        double points = band(e1, 4) + band(e3, 4) + band(e6, 3) + band(e3vs50, 4);

        boolean outperforming = !Double.isNaN(e3) && e3 > 0;
        String label;
        double lead = Double.isNaN(e3) ? e1 : e3;
        if (Double.isNaN(lead)) label = "UNKNOWN";
        else if (lead >= 15) label = "MARKET LEADER";
        else if (lead >= 5) label = "OUTPERFORMING";
        else if (lead > 0) label = "SLIGHTLY AHEAD";
        else if (lead > -10) label = "LAGGING";
        else label = "UNDERPERFORMING";

        return new RelativeStrength(e1, e3, e6, e3vs50, bench1, bench3, bench6,
                outperforming, label, points);
    }

    /**
     * Excess return mapped onto a component's points. The bands are wide on purpose: the
     * difference between +4% and +5% of excess is noise, the difference between +2% and +15% is
     * not, and a linear scale would let a rounding-level edge decide a ranking position.
     *
     * <p>Underperformance scores zero rather than negative - the component floors at 0 and the
     * penalty is the points not earned.
     */
    static double band(double excessPct, double max) {
        if (Double.isNaN(excessPct)) return max * 0.5;   // unknown is neutral, not a penalty
        if (excessPct >= 15) return max;
        if (excessPct >= 8) return max * 0.85;
        if (excessPct >= 3) return max * 0.65;
        if (excessPct > 0) return max * 0.4;
        if (excessPct > -5) return max * 0.15;
        return 0;
    }

    private static double[] closes(List<Bar> bars) {
        if (bars == null || bars.size() < IndicatorSnapshot.BARS_1M + 2) return null;
        double[] out = new double[bars.size()];
        for (int i = 0; i < out.length; i++) out[i] = bars.get(i).close();
        return out;
    }

    private static double pct(double fraction) {
        return Double.isNaN(fraction) ? Double.NaN : fraction * 100;
    }

    private static double excess(double stockPct, double benchPct) {
        return Double.isNaN(stockPct) || Double.isNaN(benchPct) ? Double.NaN : stockPct - benchPct;
    }
}
