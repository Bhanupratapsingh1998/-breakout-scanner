package com.javawarriors.breakout.tradesetup;

import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Covers confirmation, volume, support, scoring, and hard-gate behavior (spec tests 11-24). */
class TradeSetupAnalyzerTest {

    private static Bar bar(long t, double o, double h, double l, double c, double v) {
        return new Bar(t, o, h, l, c, v);
    }

    private static List<Bar> downtrend(int n, double start, double dailyDeclinePct, double noisePct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double wobble = Math.sin(i * 0.5) * price * noisePct;
            double open = price;
            double close = price * (1 - dailyDeclinePct) + wobble;
            double high = Math.max(open, close) * 1.004;
            double low = Math.min(open, close) * 0.996;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            price = close;
            time += 86_400;
        }
        return bars;
    }

    private static List<Bar> uptrend(int n, double start, double dailyGrowthPct, double noisePct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double wobble = Math.sin(i * 0.5) * price * noisePct;
            double open = price;
            double close = price * (1 + dailyGrowthPct) + wobble;
            double high = Math.max(open, close) * 1.004;
            double low = Math.min(open, close) * 0.996;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            price = close;
            time += 86_400;
        }
        return bars;
    }

    private static Bar hammerBar(long time, double prevClose) {
        double open = prevClose;
        double close = prevClose * 1.01;
        double low = prevClose * 0.90;
        double high = Math.max(open, close) * 1.001;
        return bar(time, open, high, low, close, 1_800_000);
    }

    private static TradeSetupResult reversal(List<Bar> bars) {
        return new TradeSetupAnalyzer().scoreReversalSetup("TEST.NS", bars);
    }

    // ---- CONFIRMATION ----

    @Test
    void confirmation_closeAbovePatternHigh_confirmed() {
        List<Bar> bars = new ArrayList<>(downtrend(260, 500, 0.002, 0.004, 1_700_000_000L));
        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;
        double confirmClose = hammer.high() * 1.02;
        bars.add(bar(t, hammer.close(), confirmClose * 1.01, Math.min(hammer.close(), confirmClose) * 0.995, confirmClose, 2_200_000));

        TradeSetupResult r = reversal(bars);
        assertTrue(r.confirmed, "expected confirmation: " + r.classification);
        assertTrue(r.hardGates.get("breakoutOrConfirmation"));
    }

    @Test
    void confirmation_wickAboveButCloseBelow_notConfirmed() {
        List<Bar> bars = new ArrayList<>(downtrend(260, 500, 0.002, 0.004, 1_700_000_000L));
        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;
        // High pokes above the hammer's high, but the close stays below it.
        bars.add(bar(t, hammer.close(), hammer.high() * 1.03, hammer.close() * 0.995, hammer.high() * 0.99, 1_500_000));

        TradeSetupResult r = reversal(bars);
        assertFalse(r.confirmed, "an intraday wick above pattern high must not count as confirmation");
        assertEquals("WAIT_FOR_CONFIRMATION", r.classification);
    }

    @Test
    void confirmation_afterAllowedWindow_notCounted() {
        List<Bar> bars = new ArrayList<>(downtrend(260, 500, 0.002, 0.004, 1_700_000_000L));
        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;

        // 5 flat filler bars inside the confirmation window, well below the hammer's high.
        double fillerLevel = hammer.close() * 0.995;
        for (int i = 0; i < 5; i++) {
            bars.add(bar(t, fillerLevel, fillerLevel * 1.003, fillerLevel * 0.995, fillerLevel, 900_000));
            t += 86_400;
        }
        // A 6th bar, now outside the window, finally closes above the hammer's high — too late.
        double lateClose = hammer.high() * 1.02;
        bars.add(bar(t, fillerLevel, lateClose * 1.01, fillerLevel * 0.998, lateClose, 1_500_000));

        TradeSetupResult r = reversal(bars);
        assertFalse(r.confirmed, "confirmation outside the allowed window must not count");
    }

    // ---- VOLUME ----

    private static List<Bar> flatSeries(int n, double level, double dailyVolume, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            bars.add(bar(time, level, level * 1.005, level * 0.995, level, dailyVolume));
            time += 86_400;
        }
        return bars;
    }

    @Test
    void volume_ratioAtLeast1_5_isStrong() {
        List<Bar> bars = new ArrayList<>(flatSeries(210, 100, 1_000_000, 1_700_000_000L));
        Bar last = bars.get(bars.size() - 1);
        bars.set(bars.size() - 1, bar(last.time(), last.open(), last.high(), last.low(), last.close(), 1_600_000));

        TradeSetupResult r = reversal(bars);
        assertTrue(r.volumeRatio >= 1.5, "ratio=" + r.volumeRatio);
        assertEquals(1, r.scoreBreakdown.get("strongVolume"));
    }

    @Test
    void volume_ratioBelow1_5_isNotStrong() {
        List<Bar> bars = new ArrayList<>(flatSeries(210, 100, 1_000_000, 1_700_000_000L));
        Bar last = bars.get(bars.size() - 1);
        bars.set(bars.size() - 1, bar(last.time(), last.open(), last.high(), last.low(), last.close(), 1_100_000));

        TradeSetupResult r = reversal(bars);
        assertTrue(r.volumeRatio < 1.5, "ratio=" + r.volumeRatio);
        assertEquals(0, r.scoreBreakdown.get("strongVolume"));
    }

    // ---- SUPPORT ----

    /** A clean V: descends from `shoulderLevel` to `touchLevel` over 3 steps, then retraces —
     *  a single genuine pivot low at `touchLevel`, confirmed by 3 higher closes on each side. */
    private static List<Bar> vTouch(double shoulderLevel, double touchLevel, long seedTime) {
        double[] closes = {
                shoulderLevel,
                shoulderLevel - (shoulderLevel - touchLevel) * 0.33,
                shoulderLevel - (shoulderLevel - touchLevel) * 0.66,
                touchLevel,
                shoulderLevel - (shoulderLevel - touchLevel) * 0.66,
                shoulderLevel - (shoulderLevel - touchLevel) * 0.33,
                shoulderLevel,
        };
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        double prevClose = shoulderLevel * 1.01;
        for (int i = 0; i < closes.length; i++) {
            double close = closes[i];
            double open = prevClose;
            double low = i == 3 ? touchLevel : Math.min(open, close) - 0.05;
            double high = Math.max(open, close) + 0.05;
            bars.add(bar(time, open, high, low, close, 900_000));
            prevClose = close;
            time += 86_400;
        }
        return bars;
    }

    private static List<Bar> flatRun(int n, double level, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            bars.add(bar(time, level, level * 1.003, level * 0.998, level * 1.0005, 900_000));
            time += 86_400;
        }
        return bars;
    }

    @Test
    void support_nearMajorLevel_scoresTwo() {
        // Two prior V-shaped touches of the SAME level establish it as major (multi-touch)
        // support; the hammer then forms right after the second touch, at that same level.
        double touchLevel = 175.0;
        List<Bar> bars = new ArrayList<>(downtrend(60, 200, 0.001, 0.0, 1_700_000_000L));
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(200, touchLevel, t));                 // touch #1
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(flatRun(10, 195, t));                        // buffer, well above touchLevel
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(195, touchLevel, t));                 // touch #2 -> now "major" (2 touches)
        t = bars.get(bars.size() - 1).time() + 86_400;

        // Hammer: opens well above the level, wicks down to it, closes back up with a small body.
        double open = touchLevel * 1.05, close = open * 1.01, low = touchLevel;
        double high = Math.max(open, close) * 1.001;
        bars.add(bar(t, open, high, low, close, 1_500_000));

        TradeSetupResult r = reversal(bars);
        assertEquals(2, r.scoreBreakdown.get("majorSupport"), "expected the hammer to land at established multi-touch support");
    }

    // ---- SCORING COMPOSITION ----

    @Test
    void scoring_hammerVolumeSupportBreakout_correctComposition() {
        double touchLevel = 175.0;
        List<Bar> bars = new ArrayList<>(downtrend(60, 200, 0.001, 0.0, 1_700_000_000L));
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(200, touchLevel, t));
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(flatRun(10, 195, t));
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(195, touchLevel, t));
        t = bars.get(bars.size() - 1).time() + 86_400;

        double open = touchLevel * 1.05, close = open * 1.01, low = touchLevel;
        double high = Math.max(open, close) * 1.001;
        bars.add(bar(t, open, high, low, close, 900_000));
        t += 86_400;
        // Confirmation day: closes above the hammer's high, on strong volume.
        double confirmClose = high * 1.02;
        bars.add(bar(t, close, confirmClose * 1.01, close * 0.998, confirmClose, 2_000_000));

        TradeSetupResult r = reversal(bars);
        assertEquals(1, r.scoreBreakdown.get("hammer"));
        assertEquals(1, r.scoreBreakdown.get("strongVolume"));
        assertEquals(2, r.scoreBreakdown.get("majorSupport"));
        assertEquals(2, r.scoreBreakdown.get("breaksPatternHigh"));
        int expected = r.scoreBreakdown.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(expected, r.candlestickScore, "score must equal the sum of its breakdown");
    }

    @Test
    void scoring_morningStarSupportConfirmationVolume_correctComposition() {
        double touchLevel = 175.0;
        List<Bar> bars = new ArrayList<>(downtrend(60, 200, 0.001, 0.0, 1_700_000_000L));
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(200, touchLevel, t));
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(flatRun(10, 195, t));
        t = bars.get(bars.size() - 1).time() + 86_400;
        bars.addAll(vTouch(195, touchLevel, t));
        t = bars.get(bars.size() - 1).time() + 86_400;

        // Candle 1: strong bearish, landing right at the established support level.
        double c1Open = touchLevel * 1.06;
        double c1Close = touchLevel;
        bars.add(bar(t, c1Open, c1Open * 1.005, c1Close * 0.998, c1Close, 900_000));
        t += 86_400;
        // Candle 2: small indecision body.
        bars.add(bar(t, touchLevel * 0.9994, touchLevel * 1.006, touchLevel * 0.994, touchLevel * 1.0006, 900_000));
        t += 86_400;
        // Candle 3: strong bullish recovery, closing above candle 1's midpoint.
        double c3Open = touchLevel * 0.999, c3Close = touchLevel * 1.09;
        double c3High = c3Close * 1.003;
        bars.add(bar(t, c3Open, c3High, c3Open * 0.995, c3Close, 1_800_000));
        t += 86_400;
        // Confirmation day: closes above the pattern's high, on strong volume.
        double confirmClose = c3High * 1.02;
        bars.add(bar(t, c3Close, confirmClose * 1.01, c3Close * 0.998, confirmClose, 2_200_000));

        TradeSetupResult r = reversal(bars);
        assertEquals(2, r.scoreBreakdown.get("morningStar"));
        assertEquals(1, r.scoreBreakdown.get("strongVolume"));
        assertEquals(2, r.scoreBreakdown.get("majorSupport"));
        assertEquals(2, r.scoreBreakdown.get("breaksPatternHigh"));
        int expected = r.scoreBreakdown.values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(expected, r.candlestickScore, "score must equal the sum of its breakdown");
    }

    @Test
    void support_farFromAnyLevel_scoresZero() {
        // A steep, noiseless decline: no repeated-touch pivots form, and EMAs lag far behind price.
        List<Bar> bars = new ArrayList<>(downtrend(260, 500, 0.012, 0.0, 1_700_000_000L));
        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.add(hammerBar(t, lastClose));

        TradeSetupResult r = reversal(bars);
        assertEquals(0, r.scoreBreakdown.get("majorSupport"));
    }

    // ---- HARD GATES (via BREAKOUT_SETUP) ----

    private static List<Bar> cleanBreakoutSeries() {
        List<Bar> bars = new ArrayList<>(uptrend(172, 100, 0.006, 0.01, 1_700_000_000L));
        double resistanceLevel = bars.get(bars.size() - 1).close();
        for (int i = 0; i < 30; i++) {
            double wobble = Math.sin(i * 0.9) * resistanceLevel * 0.01;
            double open = resistanceLevel + wobble;
            double close = resistanceLevel - wobble * 0.6;
            bars.add(bar(bars.get(bars.size() - 1).time() + 86_400, open,
                    Math.max(open, close) + resistanceLevel * 0.003, Math.min(open, close) - resistanceLevel * 0.003,
                    close, 900_000));
        }
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double breakoutClose = resistanceLevel * 1.02;
        bars.add(bar(t, resistanceLevel * 1.005, breakoutClose * 1.01, resistanceLevel * 0.998, breakoutClose, 2_500_000));
        return bars;
    }

    @Test
    void hardGate_trendFailure_rejected() {
        List<Bar> bars = downtrend(210, 500, 0.003, 0.005, 1_700_000_000L);
        TradeSetupResult r = new TradeSetupAnalyzer().scoreBreakoutSetup("TEST.NS", bars, null, "benchmark");
        assertFalse(r.hardGates.get("trend"));
        assertEquals("REJECTED", r.classification);
    }

    @Test
    void hardGate_volumeFailure_rejected() {
        List<Bar> bars = cleanBreakoutSeries();
        Bar last = bars.get(bars.size() - 1);
        bars.set(bars.size() - 1, bar(last.time(), last.open(), last.high(), last.low(), last.close(), 500_000)); // thin volume

        TradeSetupResult r = new TradeSetupAnalyzer().scoreBreakoutSetup("TEST.NS", bars, null, "benchmark");
        assertFalse(r.hardGates.get("volume"));
        assertEquals("REJECTED", r.classification);
    }

    @Test
    void hardGate_riskRewardFailure_rejected() {
        // Extended well past the breakout line inflates risk relative to the ATR-scaled target.
        List<Bar> bars = cleanBreakoutSeries();
        Bar last = bars.get(bars.size() - 1);
        double stretchedClose = last.close() * 1.08;
        bars.set(bars.size() - 1, bar(last.time(), last.open(), stretchedClose * 1.01, last.low(), stretchedClose, last.volume()));

        TradeSetupResult r = new TradeSetupAnalyzer().scoreBreakoutSetup("TEST.NS", bars, null, "benchmark");
        assertFalse(r.hardGates.get("riskReward"));
        assertEquals("REJECTED", r.classification);
    }

    @Test
    void hardGate_allPass_tradeableClassification() {
        List<Bar> bars = cleanBreakoutSeries();
        TradeSetupResult r = new TradeSetupAnalyzer().scoreBreakoutSetup("TEST.NS", bars, null, "benchmark");
        assertTrue(r.hardGates.values().stream().allMatch(Boolean::booleanValue), "expected all gates to pass: " + r.hardGates);
        assertTrue(List.of("WATCH", "GOOD_SETUP", "HIGH_QUALITY_SETUP").contains(r.classification));
    }
}
