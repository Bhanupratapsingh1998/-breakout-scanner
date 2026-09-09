package com.javawarriors.breakout.nearbreakout;

import com.javawarriors.breakout.model.Bar;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers the pre-breakout coil detector: proximity + volatility contraction + volume dry-up. */
class NearBreakoutAnalyzerTest {

    private static Bar bar(long t, double o, double h, double l, double c, double v) {
        return new Bar(t, o, h, l, c, v);
    }

    private static List<Bar> uptrend(int n, double start, double dailyGrowthPct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double wobble = Math.sin(i * 0.7) * price * 0.003;
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

    /** A choppy, wide-range phase that sets the resistance peak. */
    private static List<Bar> choppy(int n, double level, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double swing = (i % 2 == 0 ? 1 : -1) * level * 0.03;
            double open = level + swing * 0.5;
            double close = level - swing * 0.3;
            double high = Math.max(open, close) + level * 0.01;
            double low = Math.min(open, close) - level * 0.01;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            time += 86_400;
        }
        return bars;
    }

    /** A base that tightens day by day (shrinking range), staying just under `ceiling`, with
     *  volume dropping for the final `dryUpBars` days if `dryUpVolume` — the coil itself. */
    private static List<Bar> tighteningCoil(int n, double ceiling, boolean dryUpVolume, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        double level = ceiling * 0.97;
        for (int i = 0; i < n; i++) {
            double rangePct = 0.02 - (0.02 - 0.002) * (i / (double) (n - 1)); // 2% -> 0.2%
            double wobble = (i % 2 == 0 ? 1 : -1) * level * rangePct * 0.5;
            double open = level + wobble;
            double close = level - wobble * 0.5;
            double high = Math.min(ceiling * 0.995, Math.max(open, close) + level * rangePct * 0.2);
            double low = Math.min(open, close) - level * rangePct * 0.2;
            double volume = (dryUpVolume && i >= n - 5) ? 350_000 : 1_000_000;
            bars.add(bar(time, open, high, low, close, volume));
            time += 86_400;
        }
        return bars;
    }

    private static List<Bar> buildBase(boolean contracting, boolean dryUpVolume) {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(uptrend(160, 100, 0.006, 1_700_000_000L));
        double peakLevel = bars.get(bars.size() - 1).close();
        bars.addAll(choppy(30, peakLevel, bars.get(bars.size() - 1).time() + 86_400));
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double ceiling = 0;
        for (Bar b : bars) ceiling = Math.max(ceiling, b.high());
        if (contracting) {
            bars.addAll(tighteningCoil(20, ceiling, dryUpVolume, t));
        } else {
            // Drifts gently up toward resistance with a CONSTANT-width daily range (no
            // contraction) — still dried-up volume in the last few bars if requested.
            double level = ceiling * 0.90;
            for (int i = 0; i < 20; i++) {
                level = Math.min(ceiling * 0.97, level * 1.004);
                double open = level * 0.995;
                double close = level * 1.003;
                double high = Math.min(ceiling * 0.995, level * 1.015);
                double low = level * 0.985;
                double volume = (dryUpVolume && i >= 15) ? 350_000 : 1_000_000;
                bars.add(bar(t, open, high, low, close, volume));
                t += 86_400;
            }
        }
        return bars;
    }

    private static NearBreakoutResult analyze(List<Bar> bars) {
        return new NearBreakoutAnalyzer().analyze("TEST.NS", bars);
    }

    @Test
    void coilingWithContractionAndVolumeDryUp_classifiedCoiling() {
        List<Bar> bars = buildBase(true, true);
        NearBreakoutResult r = analyze(bars);
        assertEquals("COILING", r.classification);
    }

    @Test
    void onlyVolumeDriedUp_classifiedTightening() {
        List<Bar> bars = buildBase(false, true);
        NearBreakoutResult r = analyze(bars);
        assertEquals("TIGHTENING", r.classification);
    }

    @Test
    void alreadyBrokenOut_notReturned() {
        List<Bar> bars = buildBase(true, true);
        Bar last = bars.get(bars.size() - 1);
        double breakoutClose = last.close() * 1.06; // clears resistance comfortably
        bars.set(bars.size() - 1, bar(last.time(), last.open(), breakoutClose * 1.01, last.low(), breakoutClose, 2_000_000));
        assertNull(analyze(bars), "a stock that's already broken out belongs to Breakout Scanner, not this list");
    }

    @Test
    void tooFarFromResistance_notReturned() {
        List<Bar> bars = buildBase(true, true);
        Bar last = bars.get(bars.size() - 1);
        double farClose = last.close() * 0.85; // well over 5% below resistance
        bars.set(bars.size() - 1, bar(last.time(), last.open(), last.open() * 1.005, farClose * 0.99, farClose, last.volume()));
        assertNull(analyze(bars), "more than 5% below resistance shouldn't qualify as 'near'");
    }
}
