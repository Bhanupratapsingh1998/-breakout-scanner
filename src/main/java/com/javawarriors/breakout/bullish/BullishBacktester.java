package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Replays the bullish engine across a stock's own history and reports what actually happened next.
 *
 * <p>It inherits the three anti-cheating rules from the existing {@code Backtester}, because each
 * of them silently manufactures good results if skipped:
 * <ul>
 *   <li><b>No lookahead.</b> The analyzer is re-run on a prefix ending at the candidate day, and
 *       the benchmarks are truncated to the same date - the market regime for a 2024 signal is
 *       computed from 2024's index data, not from today's.</li>
 *   <li><b>No overlapping trades.</b> After a signal the position is held to its exit and the scan
 *       resumes after it, so one long move cannot be booked five times.</li>
 *   <li><b>No unfinished trades.</b> Signals inside the final horizon are skipped; counting a trade
 *       whose outcome has not happened yet biases everything toward the most recent market.</li>
 * </ul>
 *
 * <p>Exits are the trade plan's own, not an arbitrary holding period: stop, target, or the horizon,
 * whichever comes first. When a single bar spans both the stop and the target the stop is assumed
 * to have been hit first - the daily bar cannot say which came first intraday, and assuming the
 * favourable one is how backtests flatter themselves.
 *
 * <p>Section 21 also asks that optimisation and evaluation not share a period. Nothing here tunes
 * thresholds, so there is no in-sample fit to leak; the split belongs to whoever runs it, and the
 * {@code from}/{@code to} controls exist so it can be done.
 */
public final class BullishBacktester {

    /** EMA200 plus room to converge, plus the 126 bars the 6-month return needs. */
    public static final int WARMUP_BARS = 260;

    public record Trade(long entryTime, long exitTime, int holdingDays, double entryPrice,
                        double exitPrice, double returnPct, double maxFavourablePct,
                        double maxAdversePct, String exitReason, String pattern, String tradeStatus,
                        double score) {

        public boolean isWin() {
            return returnPct > 0;
        }

        public Map<String, Object> toRow() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("entryTime", entryTime);
            m.put("exitTime", exitTime);
            m.put("holdingDays", holdingDays);
            m.put("entryPrice", entryPrice);
            m.put("exitPrice", exitPrice);
            m.put("returnPct", returnPct);
            m.put("maxFavourablePct", maxFavourablePct);
            m.put("maxAdversePct", maxAdversePct);
            m.put("exitReason", exitReason);
            m.put("pattern", pattern);
            m.put("tradeStatus", tradeStatus);
            m.put("score", score);
            return m;
        }
    }

    /** Every metric section 21 asks for, plus the trade list they were computed from. */
    public record Result(String condition, int horizonDays, int barsTested, int signals, int wins,
                         double winRatePct, double avgReturnPct, double medianReturnPct,
                         double avgWinnerPct, double avgLoserPct, double profitFactor,
                         double expectancyPct, double maxDrawdownPct, double avgHoldingDays,
                         double avgMaxFavourablePct, double avgMaxAdversePct,
                         double bestPct, double worstPct, List<Trade> trades) {

        public Map<String, Object> toRow(boolean includeTrades) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("condition", condition);
            row.put("horizonDays", horizonDays);
            row.put("barsTested", barsTested);
            row.put("signals", signals);
            if (signals > 0) {
                row.put("wins", wins);
                row.put("winRatePct", winRatePct);
                row.put("avgReturnPct", avgReturnPct);
                row.put("medianReturnPct", medianReturnPct);
                row.put("avgWinnerPct", avgWinnerPct);
                row.put("avgLoserPct", avgLoserPct);
                row.put("profitFactor", profitFactor);
                row.put("expectancyPct", expectancyPct);
                row.put("maxDrawdownPct", maxDrawdownPct);
                row.put("avgHoldingDays", avgHoldingDays);
                row.put("avgMaxFavourablePct", avgMaxFavourablePct);
                row.put("avgMaxAdversePct", avgMaxAdversePct);
                row.put("bestPct", bestPct);
                row.put("worstPct", worstPct);
                if (includeTrades) {
                    List<Map<String, Object>> t = new ArrayList<>();
                    for (Trade tr : trades) t.add(tr.toRow());
                    row.put("trades", t);
                }
            }
            return row;
        }
    }

    private BullishBacktester() {
    }

    // ---- Conditions (section 21's "test separately for" list) -------------------

    /** The engine's own entry call. */
    public static Predicate<BullishStockResult> buyNow() {
        return r -> PullbackAnalyzer.BUY_NOW.equals(r.setup().tradeStatus());
    }

    public static Predicate<BullishStockResult> withStatus(String tradeStatus) {
        return r -> tradeStatus.equals(r.setup().tradeStatus());
    }

    public static Predicate<BullishStockResult> withPattern(String patternType) {
        return r -> patternType.equals(r.pattern().type());
    }

    /** Everything that scored above a threshold, whatever the trade status said. */
    public static Predicate<BullishStockResult> scoreAtLeast(double minimum) {
        return r -> r.score().total() >= minimum;
    }

    // ---- The replay --------------------------------------------------------------

    /**
     * @param step bars to advance between evaluations. 1 evaluates every session; larger values
     *             trade resolution for speed on long ranges, since the full analysis (pattern
     *             detection included) re-runs at every evaluated bar.
     */
    public static Result run(String symbol, List<Bar> bars, List<Bar> nifty50, List<Bar> nifty500,
                             Predicate<BullishStockResult> condition, String conditionName,
                             int horizonDays, int step, BullishConfig cfg) {
        List<Trade> trades = new ArrayList<>();
        int n = bars == null ? 0 : bars.size();
        int barsTested = 0;
        int stride = Math.max(1, step);

        if (n > WARMUP_BARS + horizonDays && horizonDays > 0) {
            BullishStockAnalyzer analyzer = new BullishStockAnalyzer(cfg);
            int i = WARMUP_BARS;
            while (i < n - horizonDays) {
                barsTested++;
                long asOf = bars.get(i).time();
                List<Bar> bench50 = truncate(nifty50, asOf);
                List<Bar> bench500 = truncate(nifty500, asOf);

                BullishStockResult r;
                try {
                    MarketRegimeAnalyzer.Regime regime =
                            MarketRegimeAnalyzer.analyze(bench50, bench500, cfg);
                    r = analyzer.analyze(symbol, symbol, "BACKTEST", null,
                            bars.subList(0, i + 1), bench50, bench500, regime);
                } catch (RuntimeException e) {
                    i += stride;
                    continue;
                }

                if (condition.test(r)) {
                    Trade trade = simulate(bars, i, horizonDays, r);
                    trades.add(trade);
                    // Resume after the exit, never inside the held position.
                    i += Math.max(stride, trade.holdingDays());
                } else {
                    i += stride;
                }
            }
        }
        return summarise(conditionName, horizonDays, barsTested, trades);
    }

    /**
     * Walks the bars after entry and exits on whichever of stop / target / horizon comes first.
     * When the setup produced no trade plan, the horizon close is the only available exit.
     */
    static Trade simulate(List<Bar> bars, int entryIndex, int horizonDays, BullishStockResult r) {
        double entry = bars.get(entryIndex).close();
        TradePlanCalculator.TradePlan plan = r.tradePlan();
        boolean hasStops = plan.present();
        double stop = hasStops ? plan.stopLoss() : Double.NaN;
        double target = hasStops ? plan.target1() : Double.NaN;

        double best = entry, worst = entry;
        int last = Math.min(entryIndex + horizonDays, bars.size() - 1);

        for (int j = entryIndex + 1; j <= last; j++) {
            Bar b = bars.get(j);
            best = Math.max(best, b.high());
            worst = Math.min(worst, b.low());

            // Stop checked before target: a daily bar cannot say which was touched first, and
            // assuming the good one is how a backtest lies to you.
            if (hasStops && b.low() <= stop) {
                return trade(bars, entryIndex, j, entry, stop, best, worst, "STOP", r);
            }
            if (hasStops && b.high() >= target) {
                return trade(bars, entryIndex, j, entry, target, best, worst, "TARGET", r);
            }
        }
        return trade(bars, entryIndex, last, entry, bars.get(last).close(), best, worst, "HORIZON", r);
    }

    private static Trade trade(List<Bar> bars, int entryIndex, int exitIndex, double entry,
                               double exit, double best, double worst, String reason,
                               BullishStockResult r) {
        return new Trade(bars.get(entryIndex).time(), bars.get(exitIndex).time(),
                exitIndex - entryIndex, entry, exit, (exit / entry - 1) * 100,
                (best / entry - 1) * 100, (worst / entry - 1) * 100, reason,
                r.pattern().type(), r.setup().tradeStatus(), r.score().total());
    }

    static Result summarise(String condition, int horizonDays, int barsTested, List<Trade> trades) {
        int signals = trades.size();
        if (signals == 0) {
            return new Result(condition, horizonDays, barsTested, 0, 0, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, trades);
        }

        List<Double> returns = new ArrayList<>();
        double sum = 0, winSum = 0, lossSum = 0, mfeSum = 0, maeSum = 0, holdSum = 0;
        int wins = 0, losses = 0;
        for (Trade t : trades) {
            returns.add(t.returnPct());
            sum += t.returnPct();
            mfeSum += t.maxFavourablePct();
            maeSum += t.maxAdversePct();
            holdSum += t.holdingDays();
            if (t.isWin()) { wins++; winSum += t.returnPct(); }
            else { losses++; lossSum += t.returnPct(); }
        }

        List<Double> sorted = new ArrayList<>(returns);
        sorted.sort(Comparator.naturalOrder());
        double median = sorted.size() % 2 == 1
                ? sorted.get(sorted.size() / 2)
                : (sorted.get(sorted.size() / 2 - 1) + sorted.get(sorted.size() / 2)) / 2;

        double avgWinner = wins == 0 ? Double.NaN : winSum / wins;
        double avgLoser = losses == 0 ? Double.NaN : lossSum / losses;
        double winRate = (double) wins / signals;

        // Gross profit over gross loss. Infinite when nothing lost, which is reported as NaN
        // rather than a number that would look like a measurement.
        double grossLoss = Math.abs(lossSum);
        double profitFactor = grossLoss == 0 ? Double.NaN : winSum / grossLoss;

        // Expectancy per trade in percent, the standard formulation.
        double expectancy = winRate * (Double.isNaN(avgWinner) ? 0 : avgWinner)
                + (1 - winRate) * (Double.isNaN(avgLoser) ? 0 : avgLoser);

        return new Result(condition, horizonDays, barsTested, signals, wins, winRate * 100,
                sum / signals, median, avgWinner, avgLoser, profitFactor, expectancy,
                maxDrawdownPct(returns), holdSum / signals, mfeSum / signals, maeSum / signals,
                sorted.get(sorted.size() - 1), sorted.get(0), trades);
    }

    /**
     * Peak-to-trough drawdown of the compounded equity curve formed by taking the trades in
     * sequence. Compounded rather than additive because a 50% loss after a 50% gain is not
     * break-even, and an additive curve would report it as one.
     */
    static double maxDrawdownPct(List<Double> returnsPct) {
        double equity = 1.0, peak = 1.0, maxDd = 0;
        for (double r : returnsPct) {
            equity *= (1 + r / 100);
            peak = Math.max(peak, equity);
            maxDd = Math.max(maxDd, (peak - equity) / peak);
        }
        return maxDd * 100;
    }

    /** Benchmark bars up to and including {@code asOf}; null when nothing is left. */
    static List<Bar> truncate(List<Bar> benchmark, long asOf) {
        if (benchmark == null || benchmark.isEmpty()) return null;
        int end = 0;
        while (end < benchmark.size() && benchmark.get(end).time() <= asOf) end++;
        return end == 0 ? null : benchmark.subList(0, end);
    }
}
