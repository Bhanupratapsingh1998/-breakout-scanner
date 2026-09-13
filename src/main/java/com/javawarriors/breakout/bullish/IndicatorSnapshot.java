package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.model.Bar;

import java.util.List;

/**
 * Every indicator the bullish engine needs, computed exactly once per stock.
 *
 * <p>Nine analyzers read the same EMAs, RSI, ADX, ATR, volume averages, trailing returns and swing
 * pivots. Letting each compute its own would mean nine passes over a 375-bar series for ~500
 * stocks, and — worse — would let two analyzers silently disagree about the same number after a
 * period was tweaked in one of them. The snapshot is built once in {@link BullishStockAnalyzer}
 * and handed to all of them read-only.
 *
 * <p>The series themselves come from {@link BreakoutAnalyzer}'s public static implementations, so
 * the bullish tab's RSI is definitionally the same RSI the breakout scanner reports.
 */
public final class IndicatorSnapshot {

    /** Trading days in the standard return windows. */
    public static final int BARS_1M = 21;
    public static final int BARS_3M = 63;
    public static final int BARS_6M = 126;
    public static final int BARS_52W = 252;

    /** Fractal half-width for swing pivots: a pivot needs 3 lower bars on each side. */
    public static final int PIVOT_WINDOW = 3;

    public final String symbol;
    public final List<Bar> bars;
    public final int n;

    public final double[] open;
    public final double[] high;
    public final double[] low;
    public final double[] close;
    public final double[] volume;

    public final double[] ema20;
    public final double[] ema50;
    public final double[] ema200;
    public final double[] rsi14;
    public final double[] adx14;
    public final double[] atr14;

    public final double price;
    public final double prevClose;
    public final double lastEma20;
    public final double lastEma50;
    public final double lastEma200;
    public final double lastRsi;
    public final double lastAdx;
    public final double lastAtr;

    /** Average daily volume over the trailing 20 and 50 sessions. */
    public final double avgVolume20;
    public final double avgVolume50;
    /** Latest session's volume as a multiple of the 20-day average. */
    public final double volumeRatio;

    /** Trailing returns as fractions (0.12 = +12%). NaN when history is too short. */
    public final double return1m;
    public final double return3m;
    public final double return6m;

    /**
     * Confirmed swing pivots. The final {@link #PIVOT_WINDOW} bars are excluded because a pivot
     * cannot be confirmed until the bars after it exist — including them would let today's bar
     * masquerade as a completed swing high.
     */
    public final List<Integer> swingHighs;
    public final List<Integer> swingLows;

    private IndicatorSnapshot(String symbol, List<Bar> bars) {
        this.symbol = symbol;
        this.bars = bars;
        this.n = bars.size();

        open = new double[n];
        high = new double[n];
        low = new double[n];
        close = new double[n];
        volume = new double[n];
        for (int i = 0; i < n; i++) {
            Bar b = bars.get(i);
            open[i] = b.open();
            high[i] = b.high();
            low[i] = b.low();
            close[i] = b.close();
            volume[i] = b.volume();
        }

        ema20 = BreakoutAnalyzer.ema(close, 20);
        ema50 = BreakoutAnalyzer.ema(close, 50);
        ema200 = BreakoutAnalyzer.ema(close, 200);
        rsi14 = BreakoutAnalyzer.rsi(close, 14);
        adx14 = BreakoutAnalyzer.adx(high, low, close, 14);
        atr14 = BreakoutAnalyzer.atr(high, low, close, 14);

        price = close[n - 1];
        prevClose = n >= 2 ? close[n - 2] : price;
        lastEma20 = ema20[n - 1];
        lastEma50 = ema50[n - 1];
        lastEma200 = ema200[n - 1];
        lastRsi = rsi14[n - 1];
        lastAdx = adx14[n - 1];
        lastAtr = atr14[n - 1];

        avgVolume20 = averageVolume(20);
        avgVolume50 = averageVolume(50);
        volumeRatio = avgVolume20 == 0 ? 0 : volume[n - 1] / avgVolume20;

        return1m = BreakoutAnalyzer.trailingReturn(close, BARS_1M);
        return3m = BreakoutAnalyzer.trailingReturn(close, BARS_3M);
        return6m = BreakoutAnalyzer.trailingReturn(close, BARS_6M);

        int pivotEnd = n - PIVOT_WINDOW;
        swingHighs = BreakoutAnalyzer.swingHighIndices(high, 0, pivotEnd, PIVOT_WINDOW);
        swingLows = BreakoutAnalyzer.swingLowIndices(low, 0, pivotEnd, PIVOT_WINDOW);
    }

    public static IndicatorSnapshot of(String symbol, List<Bar> bars) {
        if (bars == null || bars.size() < 60) {
            throw new IllegalArgumentException(symbol + ": need >= 60 bars to build a snapshot, got "
                    + (bars == null ? 0 : bars.size()));
        }
        return new IndicatorSnapshot(symbol, bars);
    }

    private double averageVolume(int period) {
        int p = Math.min(period, n);
        double sum = 0;
        for (int i = n - p; i < n; i++) sum += volume[i];
        return p == 0 ? 0 : sum / p;
    }

    /** Trailing return over {@code lookback} sessions as a fraction, NaN when history is short. */
    public double trailingReturn(int lookback) {
        return BreakoutAnalyzer.trailingReturn(close, lookback);
    }

    /** Highest high over the trailing {@code lookback} sessions, inclusive of the last bar. */
    public double highestHigh(int lookback) {
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, n - lookback); i < n; i++) hi = Math.max(hi, high[i]);
        return hi;
    }

    /** Lowest low over the trailing {@code lookback} sessions, inclusive of the last bar. */
    public double lowestLow(int lookback) {
        double lo = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, n - lookback); i < n; i++) lo = Math.min(lo, low[i]);
        return lo;
    }

    /**
     * Highest high over {@code lookback} sessions ending {@code exclude} bars before the last —
     * i.e. resistance formed before the current move, not by the current move itself.
     */
    public double highestHighExcludingRecent(int lookback, int exclude) {
        int end = Math.max(1, n - exclude);
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, end - lookback); i < end; i++) hi = Math.max(hi, high[i]);
        return hi;
    }

    /** Most recent confirmed swing low's price, or the lowest low of the last 20 bars if none. */
    public double lastSwingLow() {
        if (!swingLows.isEmpty()) return low[swingLows.get(swingLows.size() - 1)];
        return lowestLow(20);
    }

    /** Nearest confirmed swing high still above {@code from}, or NaN when price is at new highs. */
    public double nearestResistanceAbove(double from) {
        double nearest = Double.NaN;
        for (int idx : swingHighs) {
            double h = high[idx];
            if (h > from && (Double.isNaN(nearest) || h < nearest)) nearest = h;
        }
        return nearest;
    }

    /** Slope of a series over the trailing {@code lookback} bars, as a fraction of its own level. */
    public static double slopePct(double[] series, int lookback) {
        int len = series.length;
        if (len <= lookback) return Double.NaN;
        double past = series[len - 1 - lookback];
        return past == 0 ? Double.NaN : (series[len - 1] - past) / past;
    }
}
