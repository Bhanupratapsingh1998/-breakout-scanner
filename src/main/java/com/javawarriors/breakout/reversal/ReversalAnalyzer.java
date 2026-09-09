package com.javawarriors.breakout.reversal;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.model.Bar;

import java.util.List;

/**
 * Detects a hammer reversal candle printed after a ~6-month structural downtrend, confirmed by
 * a later close above the hammer's high. Deliberately separate from {@link BreakoutAnalyzer} —
 * that analyzer's hard gates all assume an existing uptrend (price > EMA20 > EMA50 > EMA200),
 * which a stock coming out of a 6-month bear phase will fail by definition. Reuses
 * BreakoutAnalyzer's EMA/ATR math (public static) rather than re-implementing it.
 *
 * Unconfirmed hammers — a small body with a long lower wick that hasn't yet been cleared by a
 * later close — are deliberately NOT surfaced. A single hammer candle is noisy on its own; only
 * a confirmed follow-through is reported as an actionable signal.
 */
public class ReversalAnalyzer {

    private static final int BEARISH_LOOKBACK = 126;          // ~6 trading months
    private static final double BEARISH_RETURN_THRESHOLD = -0.15;
    private static final double HAMMER_MAX_BODY_PCT_OF_RANGE = 0.4;
    private static final double HAMMER_MIN_LOWER_WICK_MULT = 2.0;
    private static final double HAMMER_MAX_UPPER_WICK_MULT = 0.5;
    private static final int NEAR_LOW_LOOKBACK = 10;
    private static final double NEAR_LOW_TOLERANCE = 1.02;
    private static final int HAMMER_SEARCH_BARS = 5;           // how far back the hammer itself may be
    private static final int CONFIRM_RECENCY_BARS = 3;         // confirmation must be this fresh
    private static final double ATR_STOP_BUFFER = 0.25;
    private static final double TARGET_RISK_REWARD = 2.0;

    private static double trailingReturn(double[] close, int lookback) {
        int n = close.length;
        if (n <= lookback) return Double.NaN;
        double base = close[n - 1 - lookback];
        return base == 0 ? Double.NaN : (close[n - 1] - base) / base;
    }

    private static boolean isHammer(double[] open, double[] high, double[] low, double[] close, int i) {
        double range = high[i] - low[i];
        if (range <= 0) return false;
        double body = Math.abs(close[i] - open[i]);
        double lowerWick = Math.min(open[i], close[i]) - low[i];
        double upperWick = high[i] - Math.max(open[i], close[i]);
        if (body > HAMMER_MAX_BODY_PCT_OF_RANGE * range) return false;
        if (lowerWick < HAMMER_MIN_LOWER_WICK_MULT * body) return false;
        // Guard the body==0 (doji) case: fall back to a small fraction of the day's range so a
        // flat-bodied hammer still needs a genuinely negligible upper wick, not a free pass.
        return upperWick <= HAMMER_MAX_UPPER_WICK_MULT * Math.max(body, range * 0.05);
    }

    private static boolean isNearRecentLow(double[] low, int i, int lookback) {
        int from = Math.max(0, i - lookback);
        if (from >= i) return true;
        double minLow = Double.MAX_VALUE;
        for (int j = from; j < i; j++) minLow = Math.min(minLow, low[j]);
        return low[i] <= minLow * NEAR_LOW_TOLERANCE;
    }

    /** Returns the confirmed reversal signal, or null if there isn't one right now. */
    public ReversalResult analyze(String symbol, List<Bar> bars) {
        int n = bars.size();
        if (n < 200) return null;

        double[] open = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        double[] close = new double[n];
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            open[i] = b.open();
            high[i] = b.high();
            low[i] = b.low();
            close[i] = b.close();
        }

        double[] ema50 = BreakoutAnalyzer.ema(close, 50);
        double[] ema200 = BreakoutAnalyzer.ema(close, 200);
        double[] atr = BreakoutAnalyzer.atr(high, low, close, 14);

        double sixMonthReturn = trailingReturn(close, BEARISH_LOOKBACK);
        boolean bearishContext = !Double.isNaN(sixMonthReturn) && sixMonthReturn <= BEARISH_RETURN_THRESHOLD
                && ema50[n - 1] < ema200[n - 1];
        if (!bearishContext) return null;

        int latestHammerCandidate = n - 2; // needs at least one later bar to confirm it
        int earliestHammerCandidate = Math.max(NEAR_LOW_LOOKBACK, n - 1 - HAMMER_SEARCH_BARS);
        for (int i = latestHammerCandidate; i >= earliestHammerCandidate; i--) {
            if (!isHammer(open, high, low, close, i)) continue;
            if (!isNearRecentLow(low, i, NEAR_LOW_LOOKBACK)) continue;

            int confirmIndex = -1;
            for (int j = i + 1; j < n; j++) {
                if (close[j] > high[i]) { confirmIndex = j; break; }
            }
            if (confirmIndex < 0) continue;
            int confirmBarsAgo = n - 1 - confirmIndex;
            if (confirmBarsAgo > CONFIRM_RECENCY_BARS) continue;

            ReversalResult r = new ReversalResult(symbol);
            r.confirmed = true;
            r.sixMonthReturnPct = sixMonthReturn * 100;
            r.hammerLow = low[i];
            r.hammerHigh = high[i];
            r.hammerBarsAgo = n - 1 - i;
            r.confirmBarsAgo = confirmBarsAgo;
            r.currentPrice = close[n - 1];
            r.stopLoss = low[i] - ATR_STOP_BUFFER * atr[n - 1];
            double risk = r.currentPrice - r.stopLoss;
            r.target = r.currentPrice + TARGET_RISK_REWARD * risk;
            r.riskReward = risk > 0 ? (r.target - r.currentPrice) / risk : Double.NaN;
            return r;
        }
        return null;
    }
}
