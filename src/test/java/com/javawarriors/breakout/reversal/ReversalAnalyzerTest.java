package com.javawarriors.breakout.reversal;

import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the hammer-after-6-month-downtrend reversal signal: a stock that's been structurally
 * bearish, prints a capitulation-low hammer candle, and is later confirmed by a close above the
 * hammer's high. A bare hammer (no confirmation yet) or a hammer with no bearish context behind
 * it must NOT produce a signal — see ReversalAnalyzer's javadoc for why.
 */
class ReversalAnalyzerTest {

    private static Bar bar(long time, double open, double high, double low, double close, double volume) {
        return new Bar(time, open, high, low, close, volume);
    }

    /** A steady decline of `n` bars from `start`, losing ~dailyDeclinePct/day with mild noise. */
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

    /** Mirror of downtrend() but rising, for the "no bearish context" negative test. */
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

    /** A textbook hammer: small body near the top of the range, long lower wick, tiny upper wick. */
    private static Bar hammerBar(long time, double prevClose) {
        double open = prevClose;
        double close = prevClose * 1.01;
        double low = prevClose * 0.90;
        double high = Math.max(open, close) * 1.001;
        return bar(time, open, high, low, close, 1_800_000);
    }

    private static ReversalResult analyze(List<Bar> bars) {
        return new ReversalAnalyzer().analyze("TEST.NS", bars);
    }

    @Test
    void hammerAfterDowntrendConfirmedNextDay_firesSignal() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(downtrend(206, 500, 0.002, 0.004, 1_700_000_000L));

        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;

        // Confirmation: closes decisively above the hammer's high.
        double confirmClose = hammer.high() * 1.02;
        bars.add(bar(t, hammer.close(), confirmClose * 1.01, Math.min(hammer.close(), confirmClose) * 0.995,
                confirmClose, 2_200_000));

        ReversalResult r = analyze(bars);

        assertNotNull(r, "expected a confirmed reversal signal");
        assertTrue(r.sixMonthReturnPct <= -15, "expected a bearish 6-month context, got " + r.sixMonthReturnPct + "%");
        assertEquals(0, r.confirmBarsAgo, "confirmation should be today's bar");
        assertEquals(1, r.hammerBarsAgo, "hammer should be yesterday's bar");
        assertTrue(r.stopLoss < r.hammerLow, "stop-loss should sit below the hammer's low, not on it");
        assertTrue(r.riskReward >= 2.0, "expected at least the target 2:1 risk/reward, got " + r.riskReward);
    }

    @Test
    void hammerWithoutBearishContext_noSignal() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(uptrend(206, 500, 0.0015, 0.004, 1_700_000_000L));

        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;

        double confirmClose = hammer.high() * 1.02;
        bars.add(bar(t, hammer.close(), confirmClose * 1.01, Math.min(hammer.close(), confirmClose) * 0.995,
                confirmClose, 2_200_000));

        assertNull(analyze(bars), "a hammer on an already-uptrending stock is not a reversal signal");
    }

    @Test
    void hammerWithoutConfirmation_noSignal() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(downtrend(206, 500, 0.002, 0.004, 1_700_000_000L));

        double lastClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        Bar hammer = hammerBar(t, lastClose);
        bars.add(hammer);
        t += 86_400;

        // Next day drifts sideways WITHOUT clearing the hammer's high — still unconfirmed.
        double nextClose = hammer.close() * 1.001;
        bars.add(bar(t, hammer.close(), hammer.high() * 0.998, hammer.close() * 0.995, nextClose, 900_000));

        assertNull(analyze(bars), "an unconfirmed hammer must not fire a signal");
    }
}
