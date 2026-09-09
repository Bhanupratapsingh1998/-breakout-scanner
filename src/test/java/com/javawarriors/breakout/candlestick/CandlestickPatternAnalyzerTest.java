package com.javawarriors.breakout.candlestick;

import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Covers HAMMER, BULLISH_ENGULFING, and MORNING_STAR detection (spec tests 1-10). */
class CandlestickPatternAnalyzerTest {

    private static Bar bar(long t, double o, double h, double l, double c, double v) {
        return new Bar(t, o, h, l, c, v);
    }

    /** A steady decline of `n` bars, losing ~dailyDeclinePct/day. */
    private static List<Bar> decline(int n, double start, double dailyDeclinePct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double open = price;
            double close = price * (1 - dailyDeclinePct);
            double high = Math.max(open, close) * 1.003;
            double low = Math.min(open, close) * 0.997;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            price = close;
            time += 86_400;
        }
        return bars;
    }

    private static List<Bar> uptrend(int n, double start, double dailyGrowthPct, long seedTime) {
        List<Bar> bars = new ArrayList<>();
        double price = start;
        long time = seedTime;
        for (int i = 0; i < n; i++) {
            double open = price;
            double close = price * (1 + dailyGrowthPct);
            double high = Math.max(open, close) * 1.003;
            double low = Math.min(open, close) * 0.997;
            bars.add(bar(time, open, high, low, close, 1_000_000));
            price = close;
            time += 86_400;
        }
        return bars;
    }

    // ---- HAMMER ----

    @Test
    void hammer_validAfterDecline_detected() {
        List<Bar> bars = decline(20, 200, 0.012, 1_700_000_000L);
        double prevClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double open = prevClose, close = prevClose * 1.01, low = prevClose * 0.90;
        double high = Math.max(open, close) * 1.001;
        bars.add(bar(t, open, high, low, close, 1_500_000));

        CandlestickPattern p = CandlestickPatternAnalyzer.detectHammer(bars, bars.size() - 1);
        assertNotNull(p);
        assertEquals("HAMMER", p.type());
    }

    @Test
    void hammer_largeBody_rejected() {
        List<Bar> bars = decline(20, 200, 0.012, 1_700_000_000L);
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.add(bar(t, 100, 136, 99, 135, 1_500_000)); // body=35, range=37 -> 95% of range, not a hammer

        assertNull(CandlestickPatternAnalyzer.detectHammer(bars, bars.size() - 1));
    }

    @Test
    void hammer_shortLowerWick_rejected() {
        List<Bar> bars = decline(20, 200, 0.012, 1_700_000_000L);
        long t = bars.get(bars.size() - 1).time() + 86_400;
        bars.add(bar(t, 100, 101.2, 99.5, 101, 1_500_000)); // body=1, lowerWick=0.5 (<2x body)

        assertNull(CandlestickPatternAnalyzer.detectHammer(bars, bars.size() - 1));
    }

    @Test
    void hammer_notAtLocalLow_rejected() {
        // A steady uptrend: any small-body/long-lower-wick candle here is nowhere near a fresh low.
        List<Bar> bars = uptrend(30, 100, 0.01, 1_700_000_000L);
        double prevClose = bars.get(bars.size() - 1).close();
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double open = prevClose, close = prevClose * 1.01, low = prevClose * 0.97;
        double high = Math.max(open, close) * 1.001;
        bars.add(bar(t, open, high, low, close, 1_500_000));

        assertNull(CandlestickPatternAnalyzer.detectHammer(bars, bars.size() - 1));
    }

    // ---- BULLISH ENGULFING ----

    @Test
    void engulfing_valid_detected() {
        List<Bar> bars = List.of(
                bar(1_700_000_000L, 110, 111, 99, 100, 1_000_000),   // bearish body 110->100
                bar(1_700_086_400L, 99, 113, 98, 112, 1_200_000));   // bullish body 99->112, engulfs

        CandlestickPattern p = CandlestickPatternAnalyzer.detectBullishEngulfing(bars, 1);
        assertNotNull(p);
        assertEquals("BULLISH_ENGULFING", p.type());
    }

    @Test
    void engulfing_previousNotBearish_rejected() {
        List<Bar> bars = List.of(
                bar(1_700_000_000L, 100, 111, 99, 110, 1_000_000),   // bullish, not bearish
                bar(1_700_086_400L, 99, 113, 98, 112, 1_200_000));

        assertNull(CandlestickPatternAnalyzer.detectBullishEngulfing(bars, 1));
    }

    @Test
    void engulfing_bodyDoesNotEngulf_rejected() {
        List<Bar> bars = List.of(
                bar(1_700_000_000L, 110, 111, 99, 100, 1_000_000),   // bearish body 110->100
                bar(1_700_086_400L, 102, 109, 101, 108, 1_200_000)); // bullish but open(102) > prev close(100)

        assertNull(CandlestickPatternAnalyzer.detectBullishEngulfing(bars, 1));
    }

    // ---- MORNING STAR ----

    private static List<Bar> morningStarSetup(double c2Open, double c2Close, double c3Open, double c3Close) {
        List<Bar> bars = new ArrayList<>(decline(15, 150, 0.02, 1_700_000_000L));
        long t = bars.get(bars.size() - 1).time() + 86_400;

        // Candle 1: strong bearish.
        double c1Open = bars.get(bars.size() - 1).close();
        double c1Close = c1Open * (95.0 / 110.0);
        bars.add(bar(t, c1Open, c1Open * 1.01, c1Close * 0.99, c1Close, 1_000_000));
        t += 86_400;

        // Candle 2: small indecision body.
        bars.add(bar(t, c2Open, Math.max(c2Open, c2Close) * 1.01, Math.min(c2Open, c2Close) * 0.99, c2Close, 900_000));
        t += 86_400;

        // Candle 3: strong bullish recovery.
        bars.add(bar(t, c3Open, c3Close * 1.005, Math.min(c3Open, c3Close) * 0.995, c3Close, 1_800_000));
        return bars;
    }

    @Test
    void morningStar_valid_detected() {
        List<Bar> bars = morningStarSetup(94.8, 94.6, 94.5, 105);
        CandlestickPattern p = CandlestickPatternAnalyzer.detectMorningStar(bars, bars.size() - 1);
        assertNotNull(p);
        assertEquals("MORNING_STAR", p.type());
    }

    @Test
    void morningStar_noBearishFirstCandle_rejected() {
        List<Bar> bars = new ArrayList<>(decline(15, 150, 0.02, 1_700_000_000L));
        long t = bars.get(bars.size() - 1).time() + 86_400;
        double c1Open = bars.get(bars.size() - 1).close();
        double c1Close = c1Open * 1.05; // bullish, not bearish
        bars.add(bar(t, c1Open, c1Close * 1.01, c1Open * 0.99, c1Close, 1_000_000));
        t += 86_400;
        bars.add(bar(t, 94.8, 95.5, 94, 94.6, 900_000));
        t += 86_400;
        bars.add(bar(t, 94.5, 105.5, 94, 105, 1_800_000));

        assertNull(CandlestickPatternAnalyzer.detectMorningStar(bars, bars.size() - 1));
    }

    @Test
    void morningStar_insufficientRecovery_rejected() {
        // Same setup as the valid case, but candle 3 closes well below candle 1's midpoint.
        List<Bar> bars = morningStarSetup(94.8, 94.6, 94.5, 99);
        assertNull(CandlestickPatternAnalyzer.detectMorningStar(bars, bars.size() - 1));
    }

    // ---- OVERLAP RESOLUTION ----

    @Test
    void detectRecent_overlappingMorningStarAndEngulfing_keepsOnlyTheStrongerOne() {
        // Candle 2 is bearish (satisfies the morning-star "indecision" body) and candle 3 fully
        // engulfs it too — so the same three candles independently satisfy both MORNING_STAR
        // (candles 1-2-3) and BULLISH_ENGULFING (candles 2-3). Only the higher-ranked pattern
        // (Morning Star) should survive in the deduped list; engulfing must not also be counted.
        List<Bar> bars = morningStarSetup(94.8, 94.6, 94.5, 105);

        List<CandlestickPattern> patterns = CandlestickPatternAnalyzer.detectRecent(bars, 8);

        assertEquals(1, patterns.size(), "overlapping patterns must collapse to a single winner: " + patterns);
        assertEquals("MORNING_STAR", patterns.get(0).type());
    }
}
