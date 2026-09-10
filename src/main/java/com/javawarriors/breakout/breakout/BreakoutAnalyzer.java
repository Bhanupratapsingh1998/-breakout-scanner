package com.javawarriors.breakout.breakout;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure computation of the breakout checklist — 10 checks, 1 pt each, split into two tiers:
 *
 * HARD GATES (any failure -> REJECTED outright, soft score doesn't matter):
 *   A. Price > 20 EMA
 *   B. 20 EMA > 50 EMA
 *   C. 50 EMA > 200 EMA
 *   D. Confirmed close > resistance x1.005 (0.5% buffer)
 *   E. Breakout volume > 1.5x recent average
 *   I. Risk/reward >= 1:2 (ATR-buffered stop, objective target)
 *   J. Within 5% of the breakout level (not chased)
 *
 * SOFT SCORE (ranks stocks that clear every gate into STRONG/GOOD/WATCH):
 *   F. RSI(14) in the 55-70 strength band
 *   G. ADX(14) > 25 (trend has real strength)
 *   H. Stock outperforming its own benchmark (20-day return)
 *
 * Works off real OHLCV data — exact numbers, no image parsing.
 */
public class BreakoutAnalyzer {

    private final int resistanceLookback;
    private final int resistanceExclude;
    private final int volMaPeriod;
    private final double volMultiple;
    private final double breakoutConfirmMultiple;
    private final int rsiPeriod;
    private final double rsiLower;
    private final double rsiUpper;
    private final int adxPeriod;
    private final double adxThreshold;
    private final int relativeStrengthLookback;
    private final double minRiskReward;
    private final int atrPeriod;
    private final double atrStopMultiple;
    private final double atrTargetMultiple;
    private final double maxExtensionPct;

    public BreakoutAnalyzer() {
        this(180, 5, 20, 1.5, 1.005, 14, 55, 70, 14, 25, 20, 2.0, 14, 0.5, 3.0, 5.0);
    }

    public BreakoutAnalyzer(int resistanceLookback, int resistanceExclude, int volMaPeriod) {
        this(resistanceLookback, resistanceExclude, volMaPeriod, 1.5, 1.005, 14, 55, 70, 14, 25, 20, 2.0, 14, 0.5, 3.0, 5.0);
    }

    public BreakoutAnalyzer(int resistanceLookback, int resistanceExclude, int volMaPeriod,
                             double volMultiple, double breakoutConfirmMultiple, int rsiPeriod,
                             double rsiLower, double rsiUpper, int adxPeriod, double adxThreshold,
                             int relativeStrengthLookback, double minRiskReward, int atrPeriod,
                             double atrStopMultiple, double atrTargetMultiple, double maxExtensionPct) {
        this.resistanceLookback = resistanceLookback;
        this.resistanceExclude = resistanceExclude;
        this.volMaPeriod = volMaPeriod;
        this.volMultiple = volMultiple;
        this.breakoutConfirmMultiple = breakoutConfirmMultiple;
        this.rsiPeriod = rsiPeriod;
        this.rsiLower = rsiLower;
        this.rsiUpper = rsiUpper;
        this.adxPeriod = adxPeriod;
        this.adxThreshold = adxThreshold;
        this.relativeStrengthLookback = relativeStrengthLookback;
        this.minRiskReward = minRiskReward;
        this.atrPeriod = atrPeriod;
        this.atrStopMultiple = atrStopMultiple;
        this.atrTargetMultiple = atrTargetMultiple;
        this.maxExtensionPct = maxExtensionPct;
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
    private static double trailingReturn(double[] close, int lookback) {
        int n = close.length;
        if (n <= lookback) return Double.NaN;
        double base = close[n - 1 - lookback];
        return base == 0 ? Double.NaN : (close[n - 1] - base) / base;
    }

    /** Bar indices in [from, to) that are a local high — the max of a +/-window bar fractal. */
    private static List<Integer> swingHighIndices(double[] high, int from, int to, int window) {
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
    private static List<Integer> swingLowIndices(double[] low, int from, int to, int window) {
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

    /** HH+HL (bullish), LH+LL (bearish), or MIXED — from the last two confirmed swing highs/lows. */
    private static String classifyStructure(List<Integer> highIdx, double[] high, List<Integer> lowIdx, double[] low) {
        if (highIdx.size() < 2 || lowIdx.size() < 2) return "MIXED";
        double h1 = high[highIdx.get(highIdx.size() - 2)];
        double h2 = high[highIdx.get(highIdx.size() - 1)];
        double l1 = low[lowIdx.get(lowIdx.size() - 2)];
        double l2 = low[lowIdx.get(lowIdx.size() - 1)];
        if (h2 > h1 && l2 > l1) return "HH+HL";
        if (h2 < h1 && l2 < l1) return "LH+LL";
        return "MIXED";
    }

    public BreakoutResult analyze(String symbol, List<Bar> bars) {
        return analyze(symbol, bars, null, "benchmark");
    }

    public BreakoutResult analyze(String symbol, List<Bar> bars, List<Bar> benchmarkBars) {
        return analyze(symbol, bars, benchmarkBars, "benchmark");
    }

    public BreakoutResult analyze(String symbol, List<Bar> bars, List<Bar> benchmarkBars, String benchmarkName) {
        if (bars.size() < 200) {
            throw new IllegalArgumentException(
                    symbol + ": need >= 200 bars, got " + bars.size());
        }
        int n = bars.size();

        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        for (int i = 0; i < n; i++) {
            close[i] = bars.get(i).close();
            high[i] = bars.get(i).high();
            low[i] = bars.get(i).low();
        }

        double[] ema20 = ema(close, 20);
        double[] ema50 = ema(close, 50);
        double[] ema200 = ema(close, 200);
        double[] rsi = rsi(close, rsiPeriod);
        double[] adx = adx(high, low, close, adxPeriod);
        double[] atr = atr(high, low, close, atrPeriod);

        Bar last = bars.get(n - 1);
        double price = last.close();
        double prevClose = n >= 2 ? bars.get(n - 2).close() : price;
        double atrVal = atr[n - 1];

        // 20-period volume MA at the last bar
        double volSum = 0;
        for (int i = n - volMaPeriod; i < n; i++) volSum += bars.get(i).volume();
        double volMa = volSum / volMaPeriod;

        // Previous resistance = highest high, and swing low = lowest low, in the same
        // lookback window, excluding the most recent bars (the breakout leg itself).
        int end = n - resistanceExclude;
        int start = Math.max(0, end - resistanceLookback);
        double prevResistance = Double.MIN_VALUE;
        double swingLow = Double.MAX_VALUE;
        for (int i = start; i < end; i++) {
            prevResistance = Math.max(prevResistance, bars.get(i).high());
            swingLow = Math.min(swingLow, bars.get(i).low());
        }

        // A close that only barely pokes above resistance is a common false-breakout trap —
        // require a confirmed close at least 0.5% clear of it, not just any tick above.
        double breakoutConfirmLevel = prevResistance * breakoutConfirmMultiple;
        boolean hasBrokenOut = price > breakoutConfirmLevel;
        double extensionPct = (price - prevResistance) / prevResistance * 100;

        // Stop-loss sits an ATR buffer below the breakout level rather than exactly on it —
        // a normal retest can dip slightly under resistance before continuing, so using the
        // exact level as the stop would get shaken out by ordinary noise and make risk look
        // artificially small (and R:R artificially large).
        double stopLoss = prevResistance - atrStopMultiple * atrVal;
        double risk = price - stopLoss;

        // Target is the more conservative of two independent, objective projections — a
        // measured move (prior range height projected above resistance) and an ATR-scaled
        // near-term target — so a stock with an unusually large prior range can't produce an
        // inflated target just because the base happened to be wide.
        double measuredMoveTarget = prevResistance + (prevResistance - swingLow);
        double atrTarget = price + atrTargetMultiple * atrVal;
        double target = Math.min(measuredMoveTarget, atrTarget);
        double reward = target - price;
        double riskReward = (hasBrokenOut && risk > 0) ? reward / risk : Double.NaN;

        // Relative strength vs the benchmark matching this stock's own universe tier
        // (Nifty 50 / Nifty Next 50 / Nifty 500) over the same trailing window.
        double stockReturn = trailingReturn(close, relativeStrengthLookback);
        Double benchReturn = null;
        if (benchmarkBars != null) {
            double[] benchClose = new double[benchmarkBars.size()];
            for (int i = 0; i < benchClose.length; i++) benchClose[i] = benchmarkBars.get(i).close();
            double br = trailingReturn(benchClose, relativeStrengthLookback);
            if (!Double.isNaN(br)) benchReturn = br;
        }
        boolean outperforming = benchReturn != null && !Double.isNaN(stockReturn) && stockReturn > benchReturn;

        // ---- Price structure (HH/HL/LH/LL) from confirmed swing pivots over a wider window
        // than the resistance lookback, so structure reflects the base, not just the breakout leg.
        int structureFrom = Math.max(0, n - 90);
        int pivotWindow = 3;
        List<Integer> swingHighIdx = swingHighIndices(high, structureFrom, end, pivotWindow);
        List<Integer> swingLowIdx = swingLowIndices(low, structureFrom, end, pivotWindow);
        String structure = classifyStructure(swingHighIdx, high, swingLowIdx, low);

        // ---- Major resistance = highest high over ALL available history (up to the fetch
        // window, so effectively a 52-week/18-month high), excluding the breakout leg itself —
        // distinct from `prevResistance`, which only looks at the recent lookback window.
        double majorResistance = Double.MIN_VALUE;
        for (int i = 0; i < end; i++) majorResistance = Math.max(majorResistance, bars.get(i).high());

        // Nearest overhead resistance still above current price: the closest un-cleared swing
        // high, falling back to majorResistance, falling back to "none" (blue-sky breakout).
        double nearestResistanceAbove = Double.NaN;
        if (majorResistance > price) nearestResistanceAbove = majorResistance;
        for (Integer idx : swingHighIdx) {
            double h = high[idx];
            if (h > price && (Double.isNaN(nearestResistanceAbove) || h < nearestResistanceAbove)) {
                nearestResistanceAbove = h;
            }
        }

        // ---- Overextension detector: flag 3D/5D/10D returns that exceed a volatility-scaled
        // "normal" move for THIS stock (ATR% scaled by sqrt(time)), not one fixed % for every stock.
        double ret3d = trailingReturn(close, 3);
        double ret5d = trailingReturn(close, 5);
        double ret10d = trailingReturn(close, 10);
        double atrPct = price == 0 ? 0 : atrVal / price;
        int extensionFlags = 0;
        if (!Double.isNaN(ret3d) && ret3d > 2.0 * atrPct * Math.sqrt(3)) extensionFlags++;
        if (!Double.isNaN(ret5d) && ret5d > 2.0 * atrPct * Math.sqrt(5)) extensionFlags++;
        if (!Double.isNaN(ret10d) && ret10d > 2.0 * atrPct * Math.sqrt(10)) extensionFlags++;

        // ---- Exhaustion / profit-booking risk: observable candle behavior only (no claims
        // about who is selling) — long upper wick with a weak close, a gap-up that failed,
        // an abnormally wide range closing weak, or repeated vertical days.
        double todayRange = high[n - 1] - low[n - 1];
        double upperWickRatio = todayRange > 0 ? (high[n - 1] - price) / todayRange : 0;
        boolean weakClose = todayRange > 0 && (price - low[n - 1]) < todayRange / 2;
        boolean flagUpperWick = upperWickRatio > 0.4 && weakClose;
        boolean gapUp = prevClose > 0 && last.open() > prevClose * 1.02;
        boolean flagGapFail = gapUp && price < last.open();
        boolean flagWideRangeWeakClose = atrVal > 0 && todayRange > 1.8 * atrVal
                && todayRange > 0 && (price - low[n - 1]) < 0.3 * todayRange;
        int bigMoveDays = 0;
        for (int i = Math.max(1, n - 3); i < n; i++) {
            double dailyRet = close[i - 1] == 0 ? 0 : (close[i] - close[i - 1]) / close[i - 1];
            if (dailyRet > atrPct) bigMoveDays++;
        }
        boolean flagVerticalRun = bigMoveDays >= 2;
        int exhaustionFlags = (flagUpperWick ? 1 : 0) + (flagGapFail ? 1 : 0)
                + (flagWideRangeWeakClose ? 1 : 0) + (flagVerticalRun ? 1 : 0);
        String exhaustionRisk = exhaustionFlags >= 3 ? "HIGH" : exhaustionFlags == 2 ? "MEDIUM" : "LOW";

        // ---- Entry price-action structure: was this breakout retested and held, or is this a
        // same-day/next-day chase of the breakout candle?
        int breakoutBarIndex = -1;
        int scanFrom = Math.max(0, n - resistanceLookback);
        for (int i = scanFrom; i < n; i++) {
            if (close[i] > breakoutConfirmLevel) { breakoutBarIndex = i; break; }
        }
        int barsSinceBreakout = breakoutBarIndex < 0 ? 0 : (n - 1 - breakoutBarIndex);
        // A retest needs multiple subsequent days of price action, not just one day's intrabar
        // wick — otherwise a single extended candle's own low gets mistaken for a held retest.
        boolean retestedAndHeld = false;
        if (breakoutBarIndex >= 0 && barsSinceBreakout >= 2) {
            double minLowSince = Double.MAX_VALUE;
            for (int i = breakoutBarIndex + 1; i < n; i++) minLowSince = Math.min(minLowSince, low[i]);
            retestedAndHeld = minLowSince <= prevResistance * 1.02 && price > minLowSince;
        }
        double lastBodyMove = price - last.open();
        boolean breakoutDayChaseCandle = barsSinceBreakout <= 1 && lastBodyMove > 0.6 * atrVal;
        String entryStructure = retestedAndHeld ? "RETEST_HELD"
                : breakoutDayChaseCandle ? "CHASE_CANDLE" : "CONSOLIDATION";

        // ---- Setup Quality (/10): is this stock worth trading at all, independent of timing.
        boolean trendAligned = price > ema20[n - 1] && ema20[n - 1] > ema50[n - 1] && ema50[n - 1] > ema200[n - 1];
        int trendAlignedCount = (price > ema20[n - 1] ? 1 : 0) + (ema20[n - 1] > ema50[n - 1] ? 1 : 0)
                + (ema50[n - 1] > ema200[n - 1] ? 1 : 0);
        int trendPts = trendAligned ? 2 : (trendAlignedCount >= 2 ? 1 : 0);
        int breakoutQualityPts = !hasBrokenOut ? 0
                : (extensionPct >= 0.5 && extensionPct <= 3.0) ? 2 : (extensionPct <= 5.0 ? 1 : 0);
        double volRatio = volMa == 0 ? 0 : last.volume() / volMa;
        int volumePts = volRatio >= 2.0 ? 2 : volRatio >= 1.5 ? 1 : 0;
        // ADX is a graduated setup-quality input, not a hard gate — a stock can be starting a
        // fresh breakout while ADX is still catching up, and a hard reject would miss it.
        int adxPts = adx[n - 1] >= adxThreshold ? 2 : adx[n - 1] >= 20 ? 1 : 0;
        int structurePts = switch (structure) { case "HH+HL" -> 1; case "LH+LL" -> 0; default -> 1; };
        int rsPts;
        if (benchReturn == null || Double.isNaN(stockReturn)) rsPts = 1;
        else {
            double diffPts = (stockReturn - benchReturn) * 100;
            rsPts = diffPts > 2 ? 1 : diffPts > 0 ? 1 : 0;
        }
        int setupScore = trendPts + breakoutQualityPts + volumePts + adxPts + structurePts + rsPts;

        // ---- Entry Quality (/10): is NOW the right time to buy it — this is what's missing
        // from a single breakout checklist, and what lets a 10/10 setup still say "don't chase."
        int distancePts = !hasBrokenOut ? 0 : (extensionPct <= 3.0 ? 2 : (extensionPct <= 5.0 ? 1 : 0));
        int extensionPts = extensionFlags == 0 ? 2 : extensionFlags == 1 ? 1 : 0;
        double resistanceSpacePct = Double.isNaN(nearestResistanceAbove) ? Double.NaN
                : (nearestResistanceAbove - price) / price * 100;
        int resistanceSpacePts = Double.isNaN(resistanceSpacePct) ? 2
                : (resistanceSpacePct > 8 ? 2 : (resistanceSpacePct > 3 ? 1 : 0));
        int entryStructurePts = switch (entryStructure) { case "RETEST_HELD" -> 2; case "CHASE_CANDLE" -> 0; default -> 1; };
        int exhaustionPts = "HIGH".equals(exhaustionRisk) ? 0 : "MEDIUM".equals(exhaustionRisk) ? 1 : 2;
        int entryScore = distancePts + extensionPts + resistanceSpacePts + entryStructurePts + exhaustionPts;

        // GATE = must pass or the stock is rejected outright, regardless of soft score.
        boolean GATE = true, SOFT = false;

        BreakoutResult r = new BreakoutResult(symbol);
        r.addCheck("A. Price > 20 EMA",             price > ema20[n - 1], 1, GATE);
        r.addCheck("B. 20 EMA > 50 EMA",             ema20[n - 1] > ema50[n - 1], 1, GATE);
        r.addCheck("C. 50 EMA > 200 EMA",            ema50[n - 1] > ema200[n - 1], 1, GATE);
        r.addCheck("D. Confirmed close > resistance +0.5%", hasBrokenOut, 1, GATE);
        r.addCheck("E. Volume > 1.5x avg",           last.volume() > volMa * volMultiple, 1, GATE);
        r.addCheck("F. RSI 55-70 (strength band)",   rsi[n - 1] >= rsiLower && rsi[n - 1] <= rsiUpper, 1, SOFT);
        r.addCheck("G. ADX(14) > 25 (trending)",     adx[n - 1] > adxThreshold, 1, SOFT);
        r.addCheck("H. Outperforming " + benchmarkName, outperforming, 1, SOFT);
        r.addCheck("I. Risk/reward >= 1:2",          !Double.isNaN(riskReward) && riskReward >= minRiskReward, 1, GATE);
        r.addCheck("J. Within " + (int) maxExtensionPct + "% of breakout",
                hasBrokenOut && extensionPct <= maxExtensionPct, 1, GATE);

        // ---- Distance from the mean. Gate J caps distance above the BREAKOUT LEVEL, but that
        // level rises with every new high, so it cannot see a stock that is 40% above its 200 EMA
        // after a 60% run. This can, and it is measured in ATRs so the threshold travels across
        // quiet and volatile names alike.
        double close63 = n > 63 ? close[n - 1 - 63] : Double.NaN;
        StretchFromMean stretch = StretchFromMean.analyze(price, ema50[n - 1], ema200[n - 1],
                atrVal, close63);
        r.stretch = stretch;

        // ---- Quality of the breakout candle itself. The volume gate says how many participated;
        // this says who won the day. Computed for every stock that has a confirmed breakout bar,
        // and currently reported rather than gated — see BreakoutBarQuality.
        BreakoutBarQuality barQuality = BreakoutBarQuality.analyze(
                bars, breakoutBarIndex, breakoutBarIndex >= 0 ? atr[breakoutBarIndex] : Double.NaN);
        r.barQuality = barQuality;

        // ---- Breakout follow-through: did the breakout hold, is it being retested, or has it
        // failed outright (fallen back below the resistance it broke, not just the confirm buffer)?
        String breakoutStatus;
        if (breakoutBarIndex < 0) {
            breakoutStatus = "NOT_CONFIRMED";
        } else if (hasBrokenOut) {
            // Use the CLOSE, not just today's intraday low — a day that dipped near the line
            // but then rallied away and closed clear of it is a resolved, successful retest
            // (HOLDING), not a test still in progress.
            boolean testingTheLine = barsSinceBreakout >= 1 && price <= prevResistance * 1.02;
            breakoutStatus = testingTheLine ? "CONFIRMED_RETESTING" : "CONFIRMED_HOLDING";
        } else {
            breakoutStatus = price < prevResistance ? "FAILED" : "CONFIRMED_RETESTING";
        }

        // ---- Final classification (Setup Quality + Entry Quality, never Setup alone). A failed
        // breakout or a resistance-hugging entry overrides the scores outright — no score
        // combination should talk you into buying a broken breakout or one with no room to run.
        boolean sufficientResistanceRoom = resistanceSpacePts > 0;
        String classification;
        if ("FAILED".equals(breakoutStatus)) {
            classification = "REJECTED";
        } else if (!hasBrokenOut) {
            classification = "WAIT FOR BREAKOUT/RETEST";
        } else if (!r.passedAllGates() || setupScore < 8) {
            classification = "REJECTED";
        } else if (entryScore >= 7 && !"HIGH".equals(exhaustionRisk) && sufficientResistanceRoom
                && !stretch.isChase()
                && !Double.isNaN(riskReward) && riskReward >= minRiskReward) {
            // Note the chase check withholds BUY NOW rather than rejecting: the setup is real, the
            // price is not, so it falls through to WAIT FOR PULLBACK and stays on the watchlist.
            classification = "BUY NOW";
        } else if (entryScore >= 4) {
            classification = "WAIT FOR PULLBACK";
        } else {
            classification = "AVOID CHASING";
        }

        // ---- Entry zone: never just the current market price. Preferred = current price only
        // when entry quality is genuinely good (high entry score, low exhaustion, real room to
        // the next resistance) — otherwise fall back to the retest/support zone, so a strong
        // setup that's simply too extended right now reads as "wait for ₹X", not "buy at market."
        double recentSwingLow = swingLowIdx.isEmpty() ? swingLow : low[swingLowIdx.get(swingLowIdx.size() - 1)];
        double aggressiveEntry = price;
        double conservativeEntry = hasBrokenOut
                ? Math.min(price, Math.max(breakoutConfirmLevel, recentSwingLow + 0.25 * atrVal))
                : Double.NaN;
        boolean goodEntryNow = entryScore >= 7 && "LOW".equals(exhaustionRisk) && sufficientResistanceRoom;
        double preferredEntry = !hasBrokenOut ? Double.NaN : (goodEntryNow ? aggressiveEntry : conservativeEntry);

        r.setupScore = setupScore;
        r.entryScore = entryScore;
        r.structure = structure;
        r.exhaustionRisk = exhaustionRisk;
        r.classification = classification;
        r.breakoutStatus = breakoutStatus;

        r.values.put("Open", last.open());
        r.values.put("High", last.high());
        r.values.put("Low", last.low());
        r.values.put("Close", price);
        r.values.put("Prev Close", prevClose);
        r.values.put("EMA20", ema20[n - 1]);
        r.values.put("EMA50", ema50[n - 1]);
        r.values.put("EMA200", ema200[n - 1]);
        r.values.put("Prev resistance", prevResistance);
        r.values.put("Breakout Confirm Level", breakoutConfirmLevel);
        r.values.put("Major Resistance (52w)", majorResistance);
        if (!Double.isNaN(nearestResistanceAbove)) r.values.put("Nearest Resistance Above", nearestResistanceAbove);
        r.values.put("Volume", last.volume());
        r.values.put("Volume MA(20)", volMa);
        r.values.put("RSI(14)", rsi[n - 1]);
        r.values.put("ADX(14)", adx[n - 1]);
        r.values.put("ATR(14)", atrVal);
        r.values.put("Swing Low", swingLow);
        if (!Double.isNaN(ret3d)) r.values.put("3D Return %", ret3d * 100);
        if (!Double.isNaN(ret5d)) r.values.put("5D Return %", ret5d * 100);
        if (!Double.isNaN(ret10d)) r.values.put("10D Return %", ret10d * 100);
        if (hasBrokenOut) {
            r.values.put("Extension Above Breakout %", extensionPct);
            r.values.put("Stop Loss", stopLoss);
            r.values.put("Risk", risk);
            r.values.put("Target", target);
            r.values.put("Reward", reward);
            if (!Double.isNaN(riskReward)) r.values.put("Risk:Reward", riskReward);
            r.values.put("Aggressive Entry", aggressiveEntry);
            r.values.put("Conservative Entry", conservativeEntry);
            r.values.put("Preferred Entry", preferredEntry);
            // The bar the breakout was actually confirmed on — lets a chart mark exactly where
            // it happened, not just report the fact that it did.
            if (breakoutBarIndex >= 0) r.values.put("Breakout Bar Time", (double) bars.get(breakoutBarIndex).time());
            if (stretch.isKnown()) {
                r.values.put("Stretch Above EMA50 (ATRs)", stretch.atrsAboveEma50());
                r.values.put("Stretch Above EMA50 %", stretch.pctAboveEma50());
                if (!Double.isNaN(stretch.pctAboveEma200())) {
                    r.values.put("Stretch Above EMA200 %", stretch.pctAboveEma200());
                }
                if (!Double.isNaN(stretch.runUp63d())) {
                    r.values.put("Run-up (63d) %", stretch.runUp63d());
                }
            }
            if (barQuality.isKnown()) {
                r.values.put("Breakout Bar Close Position %", barQuality.closePosition() * 100);
                r.values.put("Breakout Bar Body %", barQuality.bodyRatio() * 100);
                r.values.put("Breakout Bar Upper Wick %", barQuality.upperWickRatio() * 100);
                if (!Double.isNaN(barQuality.rangeVsAtr())) {
                    r.values.put("Breakout Bar Range vs ATR", barQuality.rangeVsAtr());
                }
            }
        }
        if (!Double.isNaN(stockReturn)) r.values.put("Stock Return (20d) %", stockReturn * 100);
        if (benchReturn != null) r.values.put("Benchmark Return (20d) %", benchReturn * 100);
        return r;
    }
}
