package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BullishTrendAnalyzer.Trend;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BullishTrendAnalyzerTest {

    @Test
    void aSustainedUptrendIsFullyStackedAndScoresNearTheMaximum() {
        Trend t = BullishTrendAnalyzer.analyze(SeriesBuilder.healthyUptrend().snapshot());

        assertTrue(t.fullyStacked(), "price > EMA20 > EMA50 > EMA200");
        assertTrue(t.priceAboveEma200());
        assertTrue(t.ema50Rising());
        assertTrue(t.ema200Rising());
        assertTrue(t.points() >= 16,
                "a clean uptrend should score at least 16/20, got " + t.points());
        assertTrue(t.label().contains("UPTREND"), "got " + t.label());
    }

    @Test
    void aSustainedDowntrendIsNotStackedAndScoresLow() {
        Trend t = BullishTrendAnalyzer.analyze(
                SeriesBuilder.startingAt(200).move(300, -45).snapshot());

        assertFalse(t.fullyStacked());
        assertFalse(t.priceAboveEma200());
        assertFalse(t.ema50Rising());
        assertEquals("DOWNTREND", t.label());
        assertTrue(t.points() <= 4, "a downtrend should score at most 4/20, got " + t.points());
    }

    @Test
    void partialStackCreditIsDiscountedRatherThanProRated() {
        // A rollover that is bouncing: price has reclaimed the 20 EMA and the 50 EMA is still over
        // the 200, but the 20 has crossed below the 50. Two of three relations hold, and that must
        // score well below two-thirds of the aligned-stack credit — a trend in transition is not
        // most of a trend.
        Trend t = BullishTrendAnalyzer.analyze(
                SeriesBuilder.startingAt(100).move(200, 55).move(40, -15).move(12, 8).snapshot());

        assertTrue(t.priceAboveEma20(), "the bounce has reclaimed the 20 EMA");
        assertFalse(t.ema20AboveEma50(), "but the fast averages are still crossed down");
        assertTrue(t.ema50AboveEma200());
        assertTrue(t.priceAboveEma200(), "the long-term trend is still intact");

        assertFalse(t.fullyStacked());
        assertTrue(t.points() < 16, "must not score like an aligned trend, got " + t.points());
    }

    @Test
    void pointsNeverExceedTheComponentMaximum() {
        Trend t = BullishTrendAnalyzer.analyze(SeriesBuilder.healthyUptrend().snapshot());
        assertTrue(t.points() <= BullishTrendAnalyzer.MAX_POINTS);
        assertTrue(t.points() >= 0);
    }

    @Test
    void distancesAreReportedRelativeToEachMovingAverage() {
        Trend t = BullishTrendAnalyzer.analyze(SeriesBuilder.healthyUptrend().snapshot());

        assertTrue(t.distanceFromEma20Pct() > 0);
        assertTrue(t.distanceFromEma200Pct() > t.distanceFromEma50Pct(),
                "the 200 EMA lags furthest behind price in a long uptrend");
    }
}
