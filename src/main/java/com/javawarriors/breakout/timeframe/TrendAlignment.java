package com.javawarriors.breakout.timeframe;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.model.Bar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the trend on each of the daily, weekly and monthly series and reports whether they agree.
 *
 * <p>The problem this exists to solve: a daily breakout inside a falling weekly trend is a bull
 * trap. The daily checklist can score it 10/10 — price over a rising 20 EMA, expanding volume,
 * a clean close above resistance — while the stock is really just retracing inside a larger
 * downtrend, which is exactly the setup that reverses days after entry.
 *
 * <p>Each timeframe is judged on the same two questions, so the three read consistently:
 * is price above the fast EMA, and is the fast EMA above the slow one. UP needs both, DOWN needs
 * neither, anything else is MIXED.
 */
public final class TrendAlignment {

    /** Spans are per-timeframe, not rescaled daily values: 20/50 weeks is ~5 months / ~1 year. */
    private static final int DAILY_FAST = 20, DAILY_SLOW = 50;
    private static final int WEEKLY_FAST = 20, WEEKLY_SLOW = 50;
    private static final int MONTHLY_FAST = 6, MONTHLY_SLOW = 12;

    /**
     * Below this many bars an EMA has not converged and its reading is an artifact of the seed
     * value, so the timeframe reports UNKNOWN rather than a number that looks authoritative.
     */
    private static final int MIN_BARS = 8;

    public record TrendState(String timeframe, String trend, double price, double fastEma,
                             double slowEma, int bars) {

        public boolean isUp() {
            return "UP".equals(trend);
        }

        public boolean isDown() {
            return "DOWN".equals(trend);
        }

        public boolean isKnown() {
            return !"UNKNOWN".equals(trend);
        }

        /**
         * Fast EMA under slow EMA: the timeframe's structure is bearish regardless of where this
         * period's price sits. This, not {@link #isDown()}, is what a higher timeframe gets judged
         * on — any sharp bounce lifts price back over the fast EMA while the stock is still far
         * below its slow EMA, and treating that as neutral is what lets a bull trap through.
         */
        public boolean structureBearish() {
            return isKnown() && fastEma < slowEma;
        }

        public boolean structureBullish() {
            return isKnown() && fastEma > slowEma;
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("timeframe", timeframe);
            row.put("trend", trend);
            row.put("bars", bars);
            if (isKnown()) {
                row.put("price", price);
                row.put("fastEma", fastEma);
                row.put("slowEma", slowEma);
                row.put("structure", structureBearish() ? "BEARISH" : structureBullish() ? "BULLISH" : "FLAT");
            }
            return row;
        }
    }

    public record Alignment(TrendState daily, TrendState weekly, TrendState monthly) {

        /** How many of the three known timeframes are trending up. */
        public int upCount() {
            int n = 0;
            for (TrendState s : new TrendState[] {daily, weekly, monthly}) {
                if (s.isUp()) n++;
            }
            return n;
        }

        /**
         * ALIGNED_UP  - every known timeframe agrees the trend is up: a daily breakout here has
         *               the weekly and monthly behind it.
         * COUNTER     - a higher timeframe's EMA structure is bearish. This is the bull-trap
         *               case and the reason the whole class exists: the daily chart can print a
         *               textbook breakout while the weekly 20 EMA is still far under its 50.
         * MIXED       - higher timeframes are neither confirming nor contradicting.
         */
        public String verdict() {
            if (weekly.structureBearish() || monthly.structureBearish()) return "COUNTER";
            if (daily.isUp() && weekly.structureBullish()
                    && (monthly.structureBullish() || !monthly.isKnown())) return "ALIGNED_UP";
            return "MIXED";
        }

        /** True when no higher timeframe contradicts a long entry. */
        public boolean supportsLong() {
            return !"COUNTER".equals(verdict());
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("verdict", verdict());
            row.put("upCount", upCount());
            row.put("daily", daily.toRow());
            row.put("weekly", weekly.toRow());
            row.put("monthly", monthly.toRow());
            return row;
        }
    }

    private TrendAlignment() {
    }

    /** Aggregates the daily series internally — callers pass the same bars the daily scan used. */
    public static Alignment analyze(List<Bar> daily) {
        return new Alignment(
                state("DAILY", daily, DAILY_FAST, DAILY_SLOW),
                state("WEEKLY", TimeframeAggregator.toWeekly(daily), WEEKLY_FAST, WEEKLY_SLOW),
                state("MONTHLY", TimeframeAggregator.toMonthly(daily), MONTHLY_FAST, MONTHLY_SLOW));
    }

    private static TrendState state(String timeframe, List<Bar> bars, int fastSpan, int slowSpan) {
        int n = bars == null ? 0 : bars.size();
        if (n < MIN_BARS) {
            return new TrendState(timeframe, "UNKNOWN", Double.NaN, Double.NaN, Double.NaN, n);
        }

        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = bars.get(i).close();

        // Span is capped to the data actually available so a short series degrades to a faster
        // average rather than returning the seed value dressed up as a 50-period EMA.
        double fast = BreakoutAnalyzer.ema(close, Math.min(fastSpan, n))[n - 1];
        double slow = BreakoutAnalyzer.ema(close, Math.min(slowSpan, n))[n - 1];
        double price = close[n - 1];

        String trend;
        if (price > fast && fast > slow) trend = "UP";
        else if (price < fast && fast < slow) trend = "DOWN";
        else trend = "MIXED";

        return new TrendState(timeframe, trend, price, fast, slow, n);
    }
}
