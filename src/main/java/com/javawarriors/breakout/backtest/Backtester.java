package com.javawarriors.breakout.backtest;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.breakout.BreakoutResult;
import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Replays an entry condition across a stock's own history and reports what happened next.
 *
 * <p>This is the only thing in the scanner that can answer "does this signal actually work on this
 * stock". Everything else states an opinion about the present; this checks the opinion against the
 * past.
 *
 * <p>Three things it refuses to get wrong, because each would silently manufacture good results:
 * <ul>
 *   <li><b>Lookahead.</b> The analyzer is re-run on a prefix of the bars ending at the candidate
 *       day, never on the full series. The benchmark is truncated to the same date too - its last
 *       20 bars feed the relative-strength check, so handing over the whole series would compare a
 *       2024 entry against 2026's index.</li>
 *   <li><b>Overlapping trades.</b> A condition that holds for six days running would otherwise book
 *       the same move six times. After a signal the next {@code horizonDays} bars are skipped, so
 *       every recorded trade is independent.</li>
 *   <li><b>Unfinished trades.</b> Signals in the final {@code horizonDays} bars are ignored: their
 *       outcome has not happened yet, and counting whatever return has accrued so far biases the
 *       result toward whatever the market did most recently.</li>
 * </ul>
 */
public final class Backtester {

    /** EMA200 plus room for it to converge before any signal is trusted. */
    public static final int WARMUP_BARS = 250;

    public record Trade(long entryTime, double entryPrice, double exitPrice, double returnPct,
                        double maxAdversePct) {}

    public record Result(String condition, int horizonDays, int barsTested, int signals, int wins,
                         double winRatePct, double avgReturnPct, double medianReturnPct,
                         double bestPct, double worstPct, double avgMaxAdversePct,
                         List<Trade> trades) {

        public Map<String, Object> toRow() {
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
                row.put("bestPct", bestPct);
                row.put("worstPct", worstPct);
                row.put("avgMaxAdversePct", avgMaxAdversePct);
                List<Map<String, Object>> t = new ArrayList<>();
                for (Trade tr : trades) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("entryTime", tr.entryTime());
                    m.put("entryPrice", tr.entryPrice());
                    m.put("exitPrice", tr.exitPrice());
                    m.put("returnPct", tr.returnPct());
                    m.put("maxAdversePct", tr.maxAdversePct());
                    t.add(m);
                }
                row.put("trades", t);
            }
            return row;
        }
    }

    private Backtester() {
    }

    /** The scanner's own live verdict: would this have shown BUY NOW on that day. */
    public static Predicate<BreakoutResult> buyNow() {
        return r -> "BUY NOW".equals(r.classification);
    }

    /** Looser: every hard gate passed, whatever the entry-timing verdict said. */
    public static Predicate<BreakoutResult> allGatesPassed() {
        return BreakoutResult::passedAllGates;
    }

    public static Result run(List<Bar> bars, List<Bar> benchmark, String benchmarkName,
                             Predicate<BreakoutResult> condition, String conditionName,
                             int horizonDays) {
        return run(bars, benchmark, benchmarkName, condition, conditionName, horizonDays,
                new BreakoutAnalyzer());
    }

    public static Result run(List<Bar> bars, List<Bar> benchmark, String benchmarkName,
                             Predicate<BreakoutResult> condition, String conditionName,
                             int horizonDays, BreakoutAnalyzer analyzer) {
        List<Trade> trades = new ArrayList<>();
        int n = bars == null ? 0 : bars.size();
        int barsTested = 0;

        if (n > WARMUP_BARS + horizonDays && horizonDays > 0) {
            int i = WARMUP_BARS;
            while (i < n - horizonDays) {
                barsTested++;
                BreakoutResult r;
                try {
                    r = analyzer.analyze("BT", bars.subList(0, i + 1),
                            truncate(benchmark, bars.get(i).time()), benchmarkName);
                } catch (RuntimeException e) {
                    i++;
                    continue;
                }

                if (condition.test(r)) {
                    double entry = bars.get(i).close();
                    double exit = bars.get(i + horizonDays).close();
                    double worst = entry;
                    for (int j = i + 1; j <= i + horizonDays; j++) {
                        worst = Math.min(worst, bars.get(j).low());
                    }
                    trades.add(new Trade(bars.get(i).time(), entry, exit,
                            (exit / entry - 1) * 100, (worst / entry - 1) * 100));
                    // Skip the whole holding period so trades never overlap.
                    i += horizonDays;
                } else {
                    i++;
                }
            }
        }

        return summarise(conditionName, horizonDays, barsTested, trades);
    }

    /**
     * Benchmark bars up to and including {@code asOf}. Returns null when nothing is left, which the
     * analyzer treats as "no benchmark" rather than as an empty comparison.
     */
    private static List<Bar> truncate(List<Bar> benchmark, long asOf) {
        if (benchmark == null || benchmark.isEmpty()) return null;
        int end = 0;
        while (end < benchmark.size() && benchmark.get(end).time() <= asOf) end++;
        return end == 0 ? null : benchmark.subList(0, end);
    }

    private static Result summarise(String condition, int horizonDays, int barsTested, List<Trade> trades) {
        int signals = trades.size();
        if (signals == 0) {
            return new Result(condition, horizonDays, barsTested, 0, 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, trades);
        }
        List<Double> returns = new ArrayList<>();
        double sum = 0, adverseSum = 0;
        int wins = 0;
        for (Trade t : trades) {
            returns.add(t.returnPct());
            sum += t.returnPct();
            adverseSum += t.maxAdversePct();
            if (t.returnPct() > 0) wins++;
        }
        returns.sort(Comparator.naturalOrder());
        double median = returns.size() % 2 == 1
                ? returns.get(returns.size() / 2)
                : (returns.get(returns.size() / 2 - 1) + returns.get(returns.size() / 2)) / 2;

        return new Result(condition, horizonDays, barsTested, signals, wins,
                (double) wins / signals * 100, sum / signals, median,
                returns.get(returns.size() - 1), returns.get(0), adverseSum / signals, trades);
    }
}
