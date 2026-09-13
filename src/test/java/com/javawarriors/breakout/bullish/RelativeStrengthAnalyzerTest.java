package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.RelativeStrengthAnalyzer.RelativeStrength;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RelativeStrengthAnalyzerTest {

    /** A stock and an index over the same window, so only the excess differs between cases. */
    private static List<Bar> index(double totalPct) {
        return SeriesBuilder.startingAt(1000).move(300, totalPct).build();
    }

    @Test
    void aStockBeatingTheIndexScoresHigherThanOneTrailingIt() {
        IndicatorSnapshot leader = SeriesBuilder.startingAt(100).move(300, 80).snapshot();
        IndicatorSnapshot laggard = SeriesBuilder.startingAt(100).move(300, 5).snapshot();
        List<Bar> market = index(30);

        RelativeStrength strong = RelativeStrengthAnalyzer.analyze(leader, market, market);
        RelativeStrength weak = RelativeStrengthAnalyzer.analyze(laggard, market, market);

        assertTrue(strong.excess3mPct() > 0, "the leader outpaced the index");
        assertTrue(weak.excess3mPct() < 0, "the laggard trailed it");
        assertTrue(strong.points() > weak.points(),
                strong.points() + " should beat " + weak.points());
        assertTrue(strong.outperformingBroadMarket());
        assertFalse(weak.outperformingBroadMarket());
    }

    @Test
    void aRisingStockInAFasterRisingMarketIsNotStrong() {
        // The case this component exists for: +25% looks excellent until the index did +45%.
        IndicatorSnapshot stock = SeriesBuilder.startingAt(100).move(300, 25).snapshot();
        List<Bar> market = index(45);

        RelativeStrength rs = RelativeStrengthAnalyzer.analyze(stock, market, market);

        assertTrue(rs.excess3mPct() < 0, "the stock underperformed despite rising");
        assertFalse(rs.outperformingBroadMarket());
        assertTrue(rs.points() < RelativeStrengthAnalyzer.MAX_POINTS * 0.5,
                "underperformance must not score in the top half, got " + rs.points());
    }

    @Test
    void missingBenchmarkDataIsNeutralRatherThanZero() {
        IndicatorSnapshot stock = SeriesBuilder.healthyUptrend().snapshot();

        RelativeStrength rs = RelativeStrengthAnalyzer.analyze(stock, null, null);

        assertEquals("UNKNOWN", rs.label());
        assertEquals(0, rs.points(), "with no benchmark at all there is nothing to score");
    }

    @Test
    void oneMissingIndexFallsBackToTheOtherRatherThanBlanking() {
        IndicatorSnapshot stock = SeriesBuilder.startingAt(100).move(300, 70).snapshot();

        RelativeStrength rs = RelativeStrengthAnalyzer.analyze(stock, null, index(20));

        assertNotEquals("UNKNOWN", rs.label());
        assertTrue(rs.excess3mPct() > 0);
        assertTrue(Double.isNaN(rs.excessVsNifty50_3mPct()),
                "the Nifty 50 comparison is unavailable and must not be invented");
    }

    @Test
    void theBandIsMonotonicAndNeverNegative() {
        double max = 4;
        assertEquals(max, RelativeStrengthAnalyzer.band(20, max));
        assertTrue(RelativeStrengthAnalyzer.band(20, max) >= RelativeStrengthAnalyzer.band(10, max));
        assertTrue(RelativeStrengthAnalyzer.band(10, max) >= RelativeStrengthAnalyzer.band(4, max));
        assertTrue(RelativeStrengthAnalyzer.band(4, max) >= RelativeStrengthAnalyzer.band(1, max));
        assertTrue(RelativeStrengthAnalyzer.band(1, max) >= RelativeStrengthAnalyzer.band(-1, max));
        assertEquals(0, RelativeStrengthAnalyzer.band(-30, max), "deep underperformance scores zero");
        assertEquals(max * 0.5, RelativeStrengthAnalyzer.band(Double.NaN, max), "unknown is neutral");
    }
}
