package com.javawarriors.breakout.nearbreakout;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.model.Bar;

import java.util.List;

/**
 * Flags stocks that haven't broken out yet but are coiling tightly right under resistance —
 * the classic volatility-contraction + volume-dry-up base that tends to precede a clean breakout.
 * Deliberately separate from {@link BreakoutAnalyzer}: that analyzer's breakout gate requires a
 * CONFIRMED close above resistance, which by definition means the move has already started. This
 * analyzer looks the other direction — "not yet, but about to" — so a trader can watch a short
 * list closely instead of discovering the move only after most of it already happened.
 *
 * Reuses {@link BreakoutAnalyzer#ema} / {@link BreakoutAnalyzer#atr}, but resistance here is a
 * 180-day high (excluding the last 5 bars) — a deliberately wider window than
 * {@link BreakoutAnalyzer}'s own 60-day breakout gate, so this list leans toward bigger, more
 * structurally significant bases rather than short-term ranges. One consequence: because the two
 * analyzers no longer share the same resistance level, a stock can clear its 60-day high (and
 * so already show up as a confirmed breakout in Breakout Scanner) while still sitting under its
 * 180-day high here — the two tabs are watching different ceilings, not strictly sequential.
 */
public class NearBreakoutAnalyzer {

    private static final int RESISTANCE_LOOKBACK = 360;
    private static final int RESISTANCE_EXCLUDE = 5;
    private static final double BREAKOUT_CONFIRM_MULTIPLE = 1.005;
    private static final double MAX_PROXIMITY_PCT = 5.0;
    private static final int ATR_PERIOD = 14;
    private static final int CONTRACTION_LOOKBACK_BARS = 10;
    private static final double CONTRACTION_RATIO_MAX = 0.85;
    private static final int VOLUME_MA_PERIOD = 20;
    private static final int VOLUME_DRYUP_LOOKBACK = 5;
    private static final double VOLUME_DRYUP_RATIO_MAX = 0.85;

    /** Returns the near-breakout signal, or null if this stock doesn't qualify at all. */
    public NearBreakoutResult analyze(String symbol, List<Bar> bars) {
        int n = bars.size();
        if (n < 200) return null;

        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = bars.get(i).close();
            high[i] = bars.get(i).high();
            low[i] = bars.get(i).low();
        }

        double[] ema20 = BreakoutAnalyzer.ema(close, 20);
        double[] ema50 = BreakoutAnalyzer.ema(close, 50);
        double[] ema200 = BreakoutAnalyzer.ema(close, 200);
        double[] atr = BreakoutAnalyzer.atr(high, low, close, ATR_PERIOD);

        double price = close[n - 1];

        // Same bullish-alignment convention as the breakout scanner's own trend gate.
        boolean trendOk = price > ema20[n - 1] && ema20[n - 1] > ema50[n - 1] && ema50[n - 1] > ema200[n - 1];
        if (!trendOk) return null;

        // Identical resistance window to BreakoutAnalyzer, so this level is exactly what would
        // need to be confirmed for a real breakout signal to fire.
        int end = n - RESISTANCE_EXCLUDE;
        int start = Math.max(0, end - RESISTANCE_LOOKBACK);
        double resistance = Double.MIN_VALUE;
        for (int i = start; i < end; i++) resistance = Math.max(resistance, bars.get(i).high());

        double breakoutConfirmLevel = resistance * BREAKOUT_CONFIRM_MULTIPLE;
        if (price >= breakoutConfirmLevel) return null; // already broken out — Breakout Scanner's territory

        double distancePct = (resistance - price) / resistance * 100;
        if (distancePct > MAX_PROXIMITY_PCT || distancePct < 0) return null;

        double atrNow = atr[n - 1];
        int contractionBar = n - 1 - CONTRACTION_LOOKBACK_BARS;
        double atrPast = contractionBar >= ATR_PERIOD ? atr[contractionBar] : atrNow;
        double contractionRatio = atrPast == 0 ? 1.0 : atrNow / atrPast;
        boolean contracting = contractionRatio <= CONTRACTION_RATIO_MAX;

        double volMa20 = average(bars, n - VOLUME_MA_PERIOD, n);
        double volAvg5 = average(bars, n - VOLUME_DRYUP_LOOKBACK, n);
        double volumeRatio = volMa20 == 0 ? 1.0 : volAvg5 / volMa20;
        boolean volumeDriedUp = volumeRatio <= VOLUME_DRYUP_RATIO_MAX;

        String classification;
        if (contracting && volumeDriedUp) classification = "COILING";
        else if (contracting || volumeDriedUp) classification = "TIGHTENING";
        else classification = "NEAR";

        NearBreakoutResult r = new NearBreakoutResult(symbol);
        r.classification = classification;
        r.currentPrice = price;
        r.resistance = resistance;
        r.breakoutConfirmLevel = breakoutConfirmLevel;
        r.distanceToResistancePct = distancePct;
        r.atrContractionRatio = contractionRatio;
        r.volumeDryUpRatio = volumeRatio;
        r.contracting = contracting;
        r.volumeDriedUp = volumeDriedUp;
        return r;
    }

    private static double average(List<Bar> bars, int from, int to) {
        int f = Math.max(0, from);
        double sum = 0;
        int count = 0;
        for (int i = f; i < to; i++) {
            sum += bars.get(i).volume();
            count++;
        }
        return count == 0 ? 0 : sum / count;
    }
}
