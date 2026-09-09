package com.javawarriors.breakout.breakout;

import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Setup-vs-Entry split added to fix the "10/10 setup bought right into a
 * profit-booking selloff" problem: a stock can score well on trend/breakout/volume/RS
 * (Setup Quality) while still being a bad moment to buy (Entry Quality) because it's
 * chasing the breakout candle or showing exhaustion signs.
 *
 * All three scenarios keep the post-breakout tail within `resistanceExclude` (5) bars —
 * `prevResistance` is recomputed from "today," excluding only the last 5 bars, so a longer
 * tail would let the rally itself leak into the resistance calculation and corrupt it.
 */
class BreakoutAnalyzerTest {

    private static Bar bar(long time, double open, double high, double low, double close, double volume) {
        return new Bar(time, open, high, low, close, volume);
    }

    /**
     * A zigzag base: `legs` up-legs of `legLen` bars each (rising `legGrowthPct` per leg),
     * separated by a single pullback bar (down `pullbackPct`). Since the overall trend rises,
     * each leg's peak is higher than the last (HH) and each pullback's trough is higher than
     * the last (HL) — a deterministic, reliably-detected HH+HL structure. A negative
     * `legGrowthPct`/`pullbackPct` flips it into a descending LH+LL zigzag instead.
     */
    private static List<Bar> zigzag(int legs, int legLen, double start, double legGrowthPct,
                                     double pullbackPct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int leg = 0; leg < legs; leg++) {
            double legTarget = price * (1 + legGrowthPct);
            double stepGrowth = Math.pow(legTarget / price, 1.0 / legLen) - 1;
            for (int i = 0; i < legLen; i++) {
                double open = price;
                double close = price * (1 + stepGrowth);
                double high = Math.max(open, close) * 1.003;
                double low = Math.min(open, close) * 0.998;
                bars.add(bar(time, open, high, low, close, 1_000_000));
                price = close;
                time += 86_400;
            }
            double open = price;
            double close = price * (1 - pullbackPct);
            double high = Math.max(open, close) * 1.002;
            double low = Math.min(open, close) * 0.997;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            price = close;
            time += 86_400;
        }
        return bars;
    }

    /** Flat sideways consolidation of `n` bars oscillating in a tight band around `level`. */
    private static List<Bar> consolidation(int n, double level, double bandPct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double wobble = Math.sin(i * 0.9) * level * bandPct;
            double open = level + wobble;
            double close = level - wobble * 0.6;
            double high = Math.max(open, close) + level * bandPct * 0.3;
            double low = Math.min(open, close) - level * bandPct * 0.3;
            bars.add(bar(time, open, high, low, close, 900_000));
            time += 86_400;
        }
        return bars;
    }

    /** Highest high over the trailing `lookback` bars — mirrors how the analyzer itself finds
     *  resistance, so test breakout levels are computed from real bar data, not assumed. */
    private static double trailingHigh(List<Bar> bars, int lookback) {
        double h = 0;
        int from = Math.max(0, bars.size() - lookback);
        for (int i = from; i < bars.size(); i++) h = Math.max(h, bars.get(i).high());
        return h;
    }

    private static BreakoutResult analyze(List<Bar> bars) {
        return new BreakoutAnalyzer().analyze("TEST.NS", bars);
    }

    @Test
    void breakoutRetestAndHold_scoresHighEntryQuality() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(zigzag(28, 6, 100, 0.03, 0.012, 1_700_000_000L));
        double base = bars.get(bars.size() - 1).close();
        bars.addAll(consolidation(12, base, 0.006, bars.get(bars.size() - 1).time() + 86_400));
        double resistanceLevel = trailingHigh(bars, 60);

        long t = bars.get(bars.size() - 1).time() + 86_400;
        // Breakout day, then exactly 4 more days (retest -> hold -> continuation -> today) so
        // the whole post-breakout arc sits inside the 5-bar resistance-exclusion window.
        double breakoutClose = resistanceLevel * 1.012;
        long breakoutTime = t;
        bars.add(bar(t, resistanceLevel * 1.002, breakoutClose * 1.003, resistanceLevel * 0.999, breakoutClose, 2_400_000));
        t += 86_400;

        double[] tailCloses = {
                resistanceLevel * 1.006,  // dips back toward the old resistance line
                resistanceLevel * 1.008,  // holds above it
                resistanceLevel * 1.009,  // turns back up
                resistanceLevel * 1.006,  // today — small continuation, healthy volume
        };
        double prevClose = breakoutClose;
        for (int i = 0; i < tailCloses.length; i++) {
            double c = tailCloses[i];
            double low = Math.min(prevClose, c) * 0.998;
            double high = Math.max(prevClose, c) * 1.002;
            double vol = i == tailCloses.length - 1 ? 2_400_000 : 900_000;
            bars.add(bar(t, prevClose, high, low, c, vol));
            prevClose = c;
            t += 86_400;
        }

        BreakoutResult r = analyze(bars);

        assertTrue(r.passedAllGates(), "expected all hard gates to pass: " + r.failedGates());
        assertTrue(r.setupScore >= 8, "expected high setup score, got " + r.setupScore);
        assertTrue(r.entryScore >= 7, "expected high entry score after a held retest, got " + r.entryScore
                + " (structure=" + r.structure + ", exhaustion=" + r.exhaustionRisk + ")");
        assertEquals("BUY NOW", r.classification);
        assertEquals((double) breakoutTime, r.values.get("Breakout Bar Time"),
                "the exposed breakout timestamp should point at the actual breakout candle, for charting");
    }

    @Test
    void gapUpFailedClose_scoresLowerEntryAndWaitsForPullback() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(zigzag(28, 6, 100, 0.03, 0.012, 1_700_000_000L));
        double base = bars.get(bars.size() - 1).close();
        bars.addAll(consolidation(12, base, 0.006, bars.get(bars.size() - 1).time() + 86_400));
        double resistanceLevel = trailingHigh(bars, 60);

        // Today gaps up hard on the breakout, spikes well above resistance intraday, then sells
        // off to close back down near resistance, red on the day — a failed gap-and-fade, one
        // of the clearest observable profit-booking signatures. Close stays high enough above
        // resistance to still clear the risk/reward gate.
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double prevClose = bars.get(bars.size() - 1).close();
        double open = prevClose * 1.028;
        double high = prevClose * 1.09;
        double close = resistanceLevel * 1.010;
        double low = Math.min(prevClose, close) * 0.998;
        bars.add(bar(t, open, high, low, close, 3_000_000));

        BreakoutResult r = analyze(bars);

        assertTrue(r.passedAllGates(), "expected all hard gates to pass: " + r.failedGates());
        assertTrue(r.setupScore >= 8, "expected the underlying stock to still look like a good setup, got " + r.setupScore);
        assertEquals("HIGH", r.exhaustionRisk, "expected the failed gap-up-and-fade to read as high exhaustion risk");
        assertEquals("WAIT FOR PULLBACK", r.classification,
                "a 10/10-ish setup with high exhaustion risk should say wait, not buy — got " + r.classification
                        + " (entry=" + r.entryScore + ")");
    }

    @Test
    void choppyLowerHighsLowerLows_penalizesSetupStructure() {
        List<Bar> bars = new ArrayList<>();
        bars.addAll(zigzag(24, 6, 100, 0.03, 0.012, 1_700_000_000L));
        double peak = bars.get(bars.size() - 1).close();
        // A declining zigzag phase (lower highs, lower lows) sitting on top of the strong prior
        // run — the long, slow-moving EMA200 stays bullish underneath while the recent swings
        // (what `structure` actually measures) turn down.
        bars.addAll(zigzag(14, 4, peak, -0.02, -0.018, bars.get(bars.size() - 1).time() + 86_400));

        double resistanceLevel = 0;
        int lookback = Math.min(60, bars.size());
        for (int i = bars.size() - lookback; i < bars.size(); i++) resistanceLevel = Math.max(resistanceLevel, bars.get(i).high());

        long t = bars.get(bars.size() - 1).time() + 86_400;
        double prevClose = bars.get(bars.size() - 1).close();
        double breakoutClose = resistanceLevel * 1.015;
        bars.add(bar(t, prevClose, breakoutClose * 1.005, prevClose * 0.998, breakoutClose, 2_200_000));

        BreakoutResult r = analyze(bars);

        assertEquals("LH+LL", r.structure, "expected the declining pre-breakout swings to read as lower-highs/lower-lows");
        assertTrue(r.setupScore <= 8, "expected LH+LL structure to cap the setup score, got " + r.setupScore);
    }
}
