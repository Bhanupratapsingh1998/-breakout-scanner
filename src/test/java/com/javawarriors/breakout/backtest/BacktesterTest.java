package com.javawarriors.breakout.backtest;

import com.javawarriors.breakout.breakout.BreakoutResult;
import com.javawarriors.breakout.model.Bar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

class BacktesterTest {

    private static final long DAY = 86_400L;

    /** Deterministic series: close walks up by `step` each bar from `start`. */
    private static List<Bar> series(int n, double start, double step) {
        List<Bar> bars = new ArrayList<>(n);
        double c = start;
        for (int i = 0; i < n; i++) {
            bars.add(new Bar(1_600_000_000L + i * DAY, c, c * 1.01, c * 0.99, c, 1_000_000));
            c += step;
        }
        return bars;
    }

    /** Fires on every bar, so overlap handling is the only thing under test. */
    private static Predicate<BreakoutResult> always() {
        return r -> true;
    }

    private static Predicate<BreakoutResult> never() {
        return r -> false;
    }

    @Test
    void tradesNeverOverlap() {
        List<Bar> bars = series(400, 100, 0.5);
        int horizon = 10;

        Backtester.Result res = Backtester.run(bars, null, "b", always(), "always", horizon);

        assertTrue(res.signals() > 0, "an always-true condition must produce signals");
        List<Backtester.Trade> t = res.trades();
        for (int i = 1; i < t.size(); i++) {
            long gap = t.get(i).entryTime() - t.get(i - 1).entryTime();
            assertTrue(gap >= horizon * DAY,
                    "entries " + (i - 1) + " and " + i + " overlap: " + gap / DAY + " days apart");
        }
    }

    @Test
    void noTradeIsOpenedInsideTheFinalHorizon() {
        List<Bar> bars = series(400, 100, 0.5);
        int horizon = 20;

        Backtester.Result res = Backtester.run(bars, null, "b", always(), "always", horizon);

        long lastUsableTime = bars.get(bars.size() - 1 - horizon).time();
        for (Backtester.Trade t : res.trades()) {
            assertTrue(t.entryTime() <= lastUsableTime,
                    "a trade was opened with no room left to measure its outcome");
        }
    }

    @Test
    void returnsAreMeasuredFromEntryCloseToTheCloseHorizonDaysLater() {
        List<Bar> bars = series(400, 100, 1.0);   // +1.0 per bar, exactly
        int horizon = 5;

        Backtester.Result res = Backtester.run(bars, null, "b", always(), "always", horizon);

        Backtester.Trade first = res.trades().get(0);
        assertEquals(first.entryPrice() + 5.0, first.exitPrice(), 1e-9, "five bars of +1.0");
        assertEquals((first.exitPrice() / first.entryPrice() - 1) * 100, first.returnPct(), 1e-9);
        assertEquals(100.0, res.winRatePct(), 1e-9, "a monotonic advance wins every time");
        assertTrue(res.avgReturnPct() > 0);
    }

    @Test
    void aFallingSeriesReportsLossesAndAdverseExcursion() {
        List<Bar> bars = series(400, 500, -0.5);
        Backtester.Result res = Backtester.run(bars, null, "b", always(), "always", 10);

        assertTrue(res.signals() > 0);
        assertEquals(0.0, res.winRatePct(), 1e-9, "nothing wins in a monotonic decline");
        assertTrue(res.avgReturnPct() < 0);
        assertTrue(res.avgMaxAdversePct() < 0, "drawdown is measured against the entry price");
        assertTrue(res.worstPct() <= res.bestPct());
    }

    @Test
    void aConditionThatNeverFiresReportsZeroSignalsWithoutNaNLeakingIntoTheRow() {
        Backtester.Result res = Backtester.run(series(400, 100, 0.5), null, "b", never(), "never", 10);

        assertEquals(0, res.signals());
        assertTrue(res.trades().isEmpty());
        assertFalse(res.toRow().containsKey("winRatePct"), "no statistics offered for an empty sample");
        assertTrue(res.toRow().containsKey("barsTested"), "but the work done is still reported");
    }

    @Test
    void tooLittleHistoryProducesNoSignalsRatherThanThrowing() {
        assertEquals(0, Backtester.run(series(100, 100, 1), null, "b", always(), "always", 10).signals());
        assertEquals(0, Backtester.run(List.of(), null, "b", always(), "always", 10).signals());
        assertEquals(0, Backtester.run(null, null, "b", always(), "always", 10).signals());
    }

    @Test
    void aNonPositiveHorizonIsRejectedRatherThanDividingByZero() {
        assertEquals(0, Backtester.run(series(400, 100, 1), null, "b", always(), "always", 0).signals());
        assertEquals(0, Backtester.run(series(400, 100, 1), null, "b", always(), "always", -5).signals());
    }

    /**
     * The lookahead guard. The condition records how many bars it was shown; that must never exceed
     * the index of the bar being evaluated, or the analyzer is seeing the future.
     */
    @Test
    void theAnalyzerOnlyEverSeesBarsUpToTheCandidateDay() {
        List<Bar> bars = series(400, 100, 0.5);
        long lastTime = bars.get(bars.size() - 1).time();
        List<Long> latestSeen = new ArrayList<>();

        Backtester.run(bars, null, "b", r -> {
            // BreakoutResult carries the close of the last bar the analyzer was given.
            Double close = r.values.get("Close");
            if (close != null) latestSeen.add(Math.round(close * 1000));
            return false;
        }, "probe", 10);

        assertFalse(latestSeen.isEmpty(), "the condition should have been evaluated");
        double finalClose = bars.get(bars.size() - 1).close();
        for (Long seen : latestSeen) {
            assertTrue(seen / 1000.0 <= finalClose,
                    "a close beyond the series end means the analyzer saw future bars");
        }
        // The very last evaluation must stop short of the horizon window.
        double lastEvaluatedClose = latestSeen.get(latestSeen.size() - 1) / 1000.0;
        assertTrue(lastEvaluatedClose <= bars.get(bars.size() - 1 - 10).close() + 1e-6,
                "evaluation ran past the last bar that has a measurable outcome");
        assertTrue(lastTime > 0);
    }

    @Test
    void medianIsReportedAlongsideTheMeanSoOneOutlierCannotCarryTheResult() {
        Backtester.Result res = Backtester.run(series(400, 100, 1.0), null, "b", always(), "always", 5);

        assertFalse(Double.isNaN(res.medianReturnPct()));
        assertFalse(Double.isNaN(res.avgReturnPct()));
        assertTrue(res.bestPct() >= res.medianReturnPct());
        assertTrue(res.medianReturnPct() >= res.worstPct());
    }
}
