package com.javawarriors.breakout.breakout;

import java.util.ArrayList;
import java.util.List;

/**
 * The shared technical-indicator library: EMA, Wilder's RSI/ATR/ADX, trailing returns and swing
 * pivots.
 *
 * <p>This class used to hold the Breakout Scanner's A-J checklist as well. That feature has been
 * removed, but these functions had always been the foundation underneath it and are still used by
 * the Bullish Stocks engine, the multi-timeframe trend check and the reversal analyzer - so the
 * maths stayed exactly as it was, in place, rather than being rewritten somewhere new. Keeping the
 * one implementation is what stops two features quietly disagreeing about the same RSI.
 *
 * <p>Everything here is a pure function of a bar series: no state, no I/O, no configuration.
 */
public final class BreakoutAnalyzer {

    private BreakoutAnalyzer() {
    }

    /** Exponential moving average, returned as a full series aligned to bars. */
    public static double[] ema(double[] values, int span) {
        double[] out = new double[values.length];
        double k = 2.0 / (span + 1);
        double prev = values[0];
        out[0] = prev;
        for (int i = 1; i < values.length; i++) {
            prev = values[i] * k + prev * (1 - k);
            out[i] = prev;
        }
        return out;
    }

    /** Wilder's RSI, returned as a full series aligned to bars (entries before `period` are 0/unset). */
    public static double[] rsi(double[] values, int period) {
        int n = values.length;
        double[] out = new double[n];
        if (n <= period) return out;

        double gainSum = 0, lossSum = 0;
        for (int i = 1; i <= period; i++) {
            double change = values[i] - values[i - 1];
            if (change > 0) gainSum += change; else lossSum += -change;
        }
        double avgGain = gainSum / period;
        double avgLoss = lossSum / period;
        out[period] = rsiFromAverages(avgGain, avgLoss);

        for (int i = period + 1; i < n; i++) {
            double change = values[i] - values[i - 1];
            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);
            avgGain = (avgGain * (period - 1) + gain) / period;
            avgLoss = (avgLoss * (period - 1) + loss) / period;
            out[i] = rsiFromAverages(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiFromAverages(double avgGain, double avgLoss) {
        if (avgLoss == 0) return 100;
        double rs = avgGain / avgLoss;
        return 100 - (100 / (1 + rs));
    }

    private static double trueRange(double high, double low, double prevClose) {
        return Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
    }

    private static double[] trueRangeSeries(double[] high, double[] low, double[] close) {
        int n = high.length;
        double[] tr = new double[n];
        for (int i = 1; i < n; i++) tr[i] = trueRange(high[i], low[i], close[i - 1]);
        return tr;
    }

    /** Wilder's ATR (average true range) — the typical daily price swing, in price units. */
    public static double[] atr(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] out = new double[n];
        if (n <= period) return out;

        double[] tr = trueRangeSeries(high, low, close);
        double sum = 0;
        for (int i = 1; i <= period; i++) sum += tr[i];
        double atrVal = sum / period;
        out[period] = atrVal;

        for (int i = period + 1; i < n; i++) {
            atrVal = (atrVal * (period - 1) + tr[i]) / period;
            out[i] = atrVal;
        }
        return out;
    }

    /** Wilder's ADX (trend strength, direction-agnostic), full series aligned to bars. */
    public static double[] adx(double[] high, double[] low, double[] close, int period) {
        int n = high.length;
        double[] out = new double[n];
        int adxStart = period * 2;
        if (n <= adxStart) return out;

        double[] tr = trueRangeSeries(high, low, close);
        double[] plusDM = new double[n];
        double[] minusDM = new double[n];
        for (int i = 1; i < n; i++) {
            double highDiff = high[i] - high[i - 1];
            double lowDiff = low[i - 1] - low[i];
            plusDM[i] = (highDiff > lowDiff && highDiff > 0) ? highDiff : 0;
            minusDM[i] = (lowDiff > highDiff && lowDiff > 0) ? lowDiff : 0;
        }

        double smTR = 0, smPlusDM = 0, smMinusDM = 0;
        for (int i = 1; i <= period; i++) {
            smTR += tr[i];
            smPlusDM += plusDM[i];
            smMinusDM += minusDM[i];
        }

        double[] dx = new double[n];
        for (int i = period; i < n; i++) {
            if (i > period) {
                smTR = smTR - (smTR / period) + tr[i];
                smPlusDM = smPlusDM - (smPlusDM / period) + plusDM[i];
                smMinusDM = smMinusDM - (smMinusDM / period) + minusDM[i];
            }
            double plusDI = smTR == 0 ? 0 : 100 * smPlusDM / smTR;
            double minusDI = smTR == 0 ? 0 : 100 * smMinusDM / smTR;
            double diSum = plusDI + minusDI;
            dx[i] = diSum == 0 ? 0 : 100 * Math.abs(plusDI - minusDI) / diSum;
        }

        double sumDX = 0;
        for (int i = period; i < adxStart; i++) sumDX += dx[i];
        double adxVal = sumDX / period;
        out[adxStart - 1] = adxVal;

        for (int i = adxStart; i < n; i++) {
            adxVal = (adxVal * (period - 1) + dx[i]) / period;
            out[i] = adxVal;
        }
        return out;
    }

    /** N-bar trailing return: (last - last[n-lookback-1]) / last[n-lookback-1]. NaN if not enough bars. */
    public static double trailingReturn(double[] close, int lookback) {
        int n = close.length;
        if (n <= lookback) return Double.NaN;
        double base = close[n - 1 - lookback];
        return base == 0 ? Double.NaN : (close[n - 1] - base) / base;
    }

    /** Bar indices in [from, to) that are a local high — the max of a +/-window bar fractal. */
    public static List<Integer> swingHighIndices(double[] high, int from, int to, int window) {
        List<Integer> out = new ArrayList<>();
        for (int i = Math.max(from, window); i < to - window; i++) {
            boolean isHigh = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && high[j] >= high[i]) { isHigh = false; break; }
            }
            if (isHigh) out.add(i);
        }
        return out;
    }

    /** Bar indices in [from, to) that are a local low — the min of a +/-window bar fractal. */
    public static List<Integer> swingLowIndices(double[] low, int from, int to, int window) {
        List<Integer> out = new ArrayList<>();
        for (int i = Math.max(from, window); i < to - window; i++) {
            boolean isLow = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && low[j] <= low[i]) { isLow = false; break; }
            }
            if (isLow) out.add(i);
        }
        return out;
    }
}
