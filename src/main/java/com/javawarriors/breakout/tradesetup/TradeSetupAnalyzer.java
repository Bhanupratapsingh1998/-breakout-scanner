package com.javawarriors.breakout.tradesetup;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.breakout.BreakoutResult;
import com.javawarriors.breakout.candlestick.CandlestickPattern;
import com.javawarriors.breakout.candlestick.CandlestickPatternAnalyzer;
import com.javawarriors.breakout.candlestick.SupportLevelDetector;
import com.javawarriors.breakout.model.Bar;

import java.util.List;

/**
 * Layers candlestick pattern detection, confirmation, volume, and support-level scoring on top
 * of the existing scanners — WITHOUT modifying BreakoutAnalyzer or ReversalAnalyzer, and without
 * fetching any data beyond the bars already pulled for a scan. Produces a single
 * {@link TradeSetupResult} shape for either setup type, so "is this stock worth trading" (the
 * existing hard gates) and "how good does the candlestick confirmation look" (the new 0-10
 * score) stay clearly separate, matching the Setup/Entry-quality split already used elsewhere in
 * this scanner.
 *
 * DESIGN NOTE — no fabricated win rates: nothing here claims "Hammer has a 60% win rate" or
 * similar. Those numbers vary wildly by market, timeframe, and exact rule set, and quoting one
 * borrowed from elsewhere would be misleading. Every field this analyzer produces (pattern type,
 * volume condition, support condition, confirmation, entry/stop/target) is exactly what would be
 * logged per-trade to compute this scanner's OWN historical win rate later, once outcomes can be
 * tracked forward — that backtesting harness doesn't exist yet and is out of scope here.
 */
public class TradeSetupAnalyzer {

    private static final int PATTERN_LOOKBACK_BARS = 8;
    private static final int CONFIRM_WINDOW_BARS = 5;
    private static final double STRONG_VOLUME_RATIO = 1.5;
    private static final double SUPPORT_TOLERANCE_PCT = 2.0;
    private static final double MIN_RISK_REWARD = 2.0;
    private static final int BEARISH_LOOKBACK = 252; // ~52 trading weeks
    private static final double BEARISH_RETURN_THRESHOLD = -0.15;
    private static final double ATR_STOP_BUFFER = 0.25;

    /** BREAKOUT_SETUP: hard gates come straight from the existing BreakoutAnalyzer checklist —
     *  trend/breakout/volume/R:R logic is not re-implemented or second-guessed here. */
    public TradeSetupResult scoreBreakoutSetup(String symbol, List<Bar> bars, List<Bar> benchmarkBars,
                                                String benchmarkName) {
        BreakoutResult br = new BreakoutAnalyzer().analyze(symbol, bars, benchmarkBars, benchmarkName);
        TradeSetupResult r = new TradeSetupResult(symbol);
        r.setupType = "BREAKOUT_SETUP";

        boolean trend = Boolean.TRUE.equals(br.checks.get("A. Price > 20 EMA"))
                && Boolean.TRUE.equals(br.checks.get("B. 20 EMA > 50 EMA"))
                && Boolean.TRUE.equals(br.checks.get("C. 50 EMA > 200 EMA"));
        boolean breakoutConfirmed = Boolean.TRUE.equals(br.checks.get("D. Confirmed close > resistance +0.5%"));
        boolean volumeGate = Boolean.TRUE.equals(br.checks.get("E. Volume > 1.5x avg"));
        boolean rrGate = Boolean.TRUE.equals(br.checks.get("I. Risk/reward >= 1:2"));

        r.hardGates.put("trend", trend);
        r.hardGates.put("breakoutOrConfirmation", breakoutConfirmed);
        r.hardGates.put("volume", volumeGate);
        r.hardGates.put("riskReward", rrGate);

        r.entry = br.values.getOrDefault("Close", 0.0);
        r.stop = br.values.get("Stop Loss");
        r.target = br.values.get("Target");
        r.riskPerShare = br.values.get("Risk");
        r.rewardPerShare = br.values.get("Reward");
        r.riskReward = br.values.get("Risk:Reward");

        applyCandlestickLayer(r, bars, bars.size(), br.values.get("EMA50"), br.values.get("EMA200"));

        boolean allGatesPass = trend && breakoutConfirmed && volumeGate && rrGate;
        r.classification = !allGatesPass
                ? "REJECTED"
                : classifyByScore(r.candlestickScore, "WATCH", "GOOD_SETUP", "HIGH_QUALITY_SETUP");
        return r;
    }

    /**
     * REVERSAL_SETUP: bearish context + reversal pattern + confirmation are the entry criteria
     * (NOT the breakout scanner's 60-day resistance breakout, which would wrongly reject a stock
     * still forming its base). Volume and R:R are still hard gates once confirmed.
     */
    public TradeSetupResult scoreReversalSetup(String symbol, List<Bar> bars) {
        TradeSetupResult r = new TradeSetupResult(symbol);
        r.setupType = "REVERSAL_SETUP";
        int n = bars.size();

        double[] close = new double[n];
        for (int i = 0; i < n; i++) close[i] = bars.get(i).close();
        double[] ema50 = BreakoutAnalyzer.ema(close, 50);
        double[] ema200 = BreakoutAnalyzer.ema(close, 200);

        double fiftyTwoWeekReturn = n > BEARISH_LOOKBACK
                ? (close[n - 1] - close[n - 1 - BEARISH_LOOKBACK]) / close[n - 1 - BEARISH_LOOKBACK]
                : Double.NaN;
        boolean bearishContext = !Double.isNaN(fiftyTwoWeekReturn) && fiftyTwoWeekReturn <= BEARISH_RETURN_THRESHOLD
                && ema50[n - 1] < ema200[n - 1];

        r.entry = close[n - 1];
        r.fiftyTwoWeekReturnPct = Double.isNaN(fiftyTwoWeekReturn) ? null : fiftyTwoWeekReturn * 100;
        applyCandlestickLayer(r, bars, n, ema50[n - 1], ema200[n - 1]);
        r.hardGates.put("trend", bearishContext);
        r.hardGates.put("breakoutOrConfirmation", r.confirmed);

        if (!bearishContext || r.patterns.isEmpty()) {
            r.hardGates.put("volume", r.volumeRatio >= STRONG_VOLUME_RATIO);
            r.hardGates.put("riskReward", false);
            r.classification = "REJECTED";
            return r;
        }
        if (!r.confirmed) {
            r.hardGates.put("volume", r.volumeRatio >= STRONG_VOLUME_RATIO);
            r.hardGates.put("riskReward", false);
            r.classification = "WAIT_FOR_CONFIRMATION";
            return r;
        }

        double[] high = new double[n];
        double[] low = new double[n];
        for (int i = 0; i < n; i++) {
            high[i] = bars.get(i).high();
            low[i] = bars.get(i).low();
        }
        double[] atr = BreakoutAnalyzer.atr(high, low, close, 14);
        CandlestickPattern latest = r.patterns.get(r.patterns.size() - 1);
        double stop = latest.patternLow() - ATR_STOP_BUFFER * atr[n - 1];
        double risk = r.entry - stop;

        Double resistance = nearestResistanceAbove(bars, n, r.entry);
        double target = (resistance != null && risk > 0 && (resistance - r.entry) / risk >= MIN_RISK_REWARD)
                ? resistance
                : r.entry + MIN_RISK_REWARD * risk; // fallback only when no reliable resistance clears the bar
        r.stop = stop;
        r.target = target;
        r.riskPerShare = risk;
        r.rewardPerShare = target - r.entry;
        r.riskReward = risk > 0 ? (target - r.entry) / risk : null;

        boolean volumeGate = r.volumeRatio >= STRONG_VOLUME_RATIO;
        boolean rrGate = risk > 0 && r.riskReward != null && r.riskReward >= MIN_RISK_REWARD;
        r.hardGates.put("volume", volumeGate);
        r.hardGates.put("riskReward", rrGate);

        if (!volumeGate || !rrGate) {
            r.classification = "CONFIRMED_BUT_NOT_TRADEABLE";
            return r;
        }
        r.classification = classifyByScore(r.candlestickScore, "WATCH", "GOOD_REVERSAL", "HIGH_QUALITY_REVERSAL");
        return r;
    }

    /** Detects patterns, scores volume/support/pattern/confirmation, and mutates `r` accordingly. */
    private void applyCandlestickLayer(TradeSetupResult r, List<Bar> bars, int n, Double ema50, Double ema200) {
        List<CandlestickPattern> patterns = CandlestickPatternAnalyzer.detectRecent(bars, PATTERN_LOOKBACK_BARS);
        r.patterns = patterns;

        int hammerPts = 0, engulfPts = 0, starPts = 0;
        for (CandlestickPattern p : patterns) {
            switch (p.type()) {
                case "HAMMER" -> hammerPts = 1;
                case "BULLISH_ENGULFING" -> engulfPts = 1;
                case "MORNING_STAR" -> starPts = 2;
                default -> { }
            }
        }
        r.scoreBreakdown.put("hammer", hammerPts);
        r.scoreBreakdown.put("bullishEngulfing", engulfPts);
        r.scoreBreakdown.put("morningStar", starPts);

        int volPeriod = Math.min(20, n);
        double volMa = 0;
        for (int i = n - volPeriod; i < n; i++) volMa += bars.get(i).volume();
        volMa /= volPeriod;
        double lastVolume = bars.get(n - 1).volume();
        r.volumeRatio = volMa == 0 ? 0 : lastVolume / volMa;
        int volumePts = r.volumeRatio >= STRONG_VOLUME_RATIO ? 1 : 0;
        r.scoreBreakdown.put("strongVolume", volumePts);

        double referenceLow = patterns.isEmpty() ? bars.get(n - 1).low() : patterns.get(patterns.size() - 1).patternLow();
        SupportLevelDetector.SupportResult support = SupportLevelDetector.nearestSupport(
                bars, n, referenceLow, SUPPORT_TOLERANCE_PCT, ema50, ema200);
        int supportPts = 0;
        if (support != null && support.distancePct() <= SUPPORT_TOLERANCE_PCT) {
            supportPts = 2;
            r.majorSupport = support.level();
            r.distanceFromSupportPct = support.distancePct();
        }
        r.scoreBreakdown.put("majorSupport", supportPts);

        int breaksHighPts = 0;
        if (!patterns.isEmpty()) {
            CandlestickPattern latest = patterns.get(patterns.size() - 1);
            r.patternHigh = latest.patternHigh();
            for (int j = latest.endIndex() + 1; j < n && (j - latest.endIndex()) <= CONFIRM_WINDOW_BARS; j++) {
                if (bars.get(j).close() > latest.patternHigh()) {
                    r.confirmed = true;
                    r.confirmationTime = bars.get(j).time();
                    r.confirmationClose = bars.get(j).close();
                    breaksHighPts = 2;
                    break;
                }
            }
        }
        r.scoreBreakdown.put("breaksPatternHigh", breaksHighPts);

        r.candlestickScore = hammerPts + engulfPts + starPts + volumePts + supportPts + breaksHighPts;
    }

    /** Nearest un-cleared swing high above `price`, or null if price is already making new highs. */
    private static Double nearestResistanceAbove(List<Bar> bars, int uptoIndexExclusive, double price) {
        int to = Math.min(uptoIndexExclusive, bars.size());
        int from = Math.max(0, to - 120);
        int window = 3;
        Double nearest = null;
        for (int i = Math.max(from, window); i < to - window; i++) {
            double hi = bars.get(i).high();
            boolean isHigh = true;
            for (int j = i - window; j <= i + window; j++) {
                if (j != i && bars.get(j).high() >= hi) { isHigh = false; break; }
            }
            if (isHigh && hi > price && (nearest == null || hi < nearest)) nearest = hi;
        }
        return nearest;
    }

    private static String classifyByScore(int score, String watch, String good, String high) {
        if (score <= 3) return watch;
        if (score <= 6) return good;
        return high;
    }
}
