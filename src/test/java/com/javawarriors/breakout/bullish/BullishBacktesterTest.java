package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BullishBacktesterTest {

    @Test
    void aWinningAndALosingTradeSummariseIntoConsistentMetrics() {
        List<BullishBacktester.Trade> trades = List.of(
                trade(10, 5), trade(-4, 3), trade(20, 8), trade(-6, 4));

        BullishBacktester.Result r = BullishBacktester.summarise("test", 20, 100, trades);

        assertEquals(4, r.signals());
        assertEquals(2, r.wins());
        assertEquals(50.0, r.winRatePct(), 1e-9);
        assertEquals(5.0, r.avgReturnPct(), 1e-9);
        assertEquals(15.0, r.avgWinnerPct(), 1e-9, "average of +10 and +20");
        assertEquals(-5.0, r.avgLoserPct(), 1e-9, "average of -4 and -6");
        assertEquals(3.0, r.profitFactor(), 1e-9, "30 gross profit over 10 gross loss");
        assertEquals(5.0, r.expectancyPct(), 1e-9, "0.5 * 15 + 0.5 * -5");
        assertEquals(5.0, r.avgHoldingDays(), 1e-9);
        assertEquals(20.0, r.bestPct(), 1e-9);
        assertEquals(-6.0, r.worstPct(), 1e-9);
    }

    @Test
    void noSignalsReportsNothingRatherThanZeroes() {
        BullishBacktester.Result r = BullishBacktester.summarise("test", 20, 500, List.of());

        assertEquals(0, r.signals());
        assertTrue(Double.isNaN(r.winRatePct()), "a 0% win rate would be a claim, not an absence");
        assertTrue(Double.isNaN(r.profitFactor()));
    }

    @Test
    void profitFactorIsNotReportedWhenNothingLost() {
        BullishBacktester.Result r = BullishBacktester.summarise("test", 20, 100,
                List.of(trade(5, 3), trade(8, 4)));

        assertEquals(100.0, r.winRatePct(), 1e-9);
        assertTrue(Double.isNaN(r.profitFactor()),
                "an infinite profit factor must not be dressed up as a measurement");
    }

    @Test
    void drawdownIsCompoundedNotAdded() {
        // +50% then -50% is a 25% loss from the peak, not break-even.
        double dd = BullishBacktester.maxDrawdownPct(List.of(50.0, -50.0));

        assertEquals(50.0, dd, 1e-9, "equity went 1.0 -> 1.5 -> 0.75, a 50% fall from the peak");
        assertEquals(0.0, BullishBacktester.maxDrawdownPct(List.of(5.0, 5.0, 5.0)),
                1e-9, "a curve that only rises has no drawdown");
    }

    @Test
    void theBenchmarkIsTruncatedToTheSignalDateSoThereIsNoLookahead() {
        List<Bar> index = SeriesBuilder.startingAt(100).move(50, 10).build();
        long asOf = index.get(19).time();

        List<Bar> truncated = BullishBacktester.truncate(index, asOf);

        assertNotNull(truncated);
        assertEquals(20, truncated.size(), "only bars up to and including the signal date");
        assertTrue(truncated.get(truncated.size() - 1).time() <= asOf);
        assertNull(BullishBacktester.truncate(index, 0L),
                "nothing available before the series starts is null, not an empty comparison");
    }

    @Test
    void aStopAndATargetInsideTheSameBarResolveToTheStop() {
        // The daily bar cannot say which was touched first; assuming the target is how a backtest
        // flatters itself, so the stop has to win.
        List<Bar> bars = List.of(
                new Bar(1, 100, 101, 99, 100, 1000),
                new Bar(2, 100, 130, 70, 100, 1000));   // spans both levels

        BullishStockResult result = resultWithPlan(100, 90, 120);
        BullishBacktester.Trade t = BullishBacktester.simulate(bars, 0, 5, result);

        assertEquals("STOP", t.exitReason());
        assertEquals(90.0, t.exitPrice(), 1e-9);
        assertTrue(t.returnPct() < 0);
    }

    @Test
    void aTradeThatHitsNeitherLevelExitsAtTheHorizon() {
        List<Bar> bars = List.of(
                new Bar(1, 100, 101, 99, 100, 1000),
                new Bar(2, 100, 104, 98, 103, 1000),
                new Bar(3, 103, 106, 102, 105, 1000));

        BullishBacktester.Trade t = BullishBacktester.simulate(bars, 0, 2, resultWithPlan(100, 90, 120));

        assertEquals("HORIZON", t.exitReason());
        assertEquals(105.0, t.exitPrice(), 1e-9);
        assertEquals(2, t.holdingDays());
        assertEquals(6.0, t.maxFavourablePct(), 1e-9, "the best the trade ever got to");
        assertEquals(-2.0, t.maxAdversePct(), 1e-9, "the worst it ever got to");
    }

    private static BullishBacktester.Trade trade(double returnPct, int holdingDays) {
        return new BullishBacktester.Trade(0, 0, holdingDays, 100, 100 * (1 + returnPct / 100),
                returnPct, Math.max(returnPct, 0), Math.min(returnPct, 0), "HORIZON",
                "NO_CLEAR_PATTERN", PullbackAnalyzer.BUY_NOW, 70);
    }

    /** A result carrying only the fields the simulator reads. */
    private static BullishStockResult resultWithPlan(double entry, double stop, double target) {
        TradePlanCalculator.TradePlan plan = new TradePlanCalculator.TradePlan(true, entry, stop,
                target, target * 1.1, 10, 20, 2.0, "AT MARKET", "SWING LOW", "RESISTANCE", 8, "test");
        PullbackAnalyzer.SetupStage setup = new PullbackAnalyzer.SetupStage(
                PullbackAnalyzer.BREAKOUT_CONFIRMED, PullbackAnalyzer.BUY_NOW, true, "test");
        BullishScoreEngine.BullishScore score = new BullishScoreEngine.BullishScore(
                0, 0, 0, 0, 0, 0, 0, 0, 80, 1.0, 80, "STRONG BULLISH");
        return new BullishStockResult("T", "T", "TEST", null, entry, entry, 0,
                null, null, null, null, null, ChartPatternDetector.ChartPattern.none(),
                null, null, plan, setup, score, "test");
    }
}
