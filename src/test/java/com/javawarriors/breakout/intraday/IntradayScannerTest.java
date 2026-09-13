package com.javawarriors.breakout.intraday;

import com.javawarriors.breakout.intraday.IntradayScanner.Signal;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two strategies, driven by hand-built 15-minute series.
 *
 * <p>Each test starts from a base series that satisfies everything except the one condition under
 * test, so a failure points at that condition rather than at the fixture.
 */
class IntradayScannerTest {

    private final IntradayConfig cfg = new IntradayConfig();

    /** 09:15 IST on a single session, so every bar lands in the same VWAP day. */
    private static final long SESSION_OPEN = 1_757_732_100L;

    private static Bar bar(long time, double o, double h, double l, double c, double v) {
        return new Bar(time, o, h, l, c, v);
    }

    /**
     * A rally strong enough to put price above its EMA20 and RSI above 65, then {@code closer} as
     * the final candle. 60 bars keeps it over the configured minimum.
     *
     * <p>The climb includes a down candle every fourth bar. Without them Wilder's RSI has no
     * average loss to divide by and pins at exactly 100, which no real stock does - and a fixture
     * sitting on that ceiling cannot demonstrate an RSI threshold gating anything.
     */
    private static List<Bar> rallyThen(java.util.function.BiFunction<Long, Double, Bar> closer) {
        List<Bar> bars = new ArrayList<>();
        double p = 100;
        long t = SESSION_OPEN;
        for (int i = 0; i < 60; i++) {
            double next = i % 4 == 3 ? p * 0.997 : p * 1.006;
            bars.add(bar(t, p, Math.max(p, next) * 1.001, Math.min(p, next) * 0.999, next, 100_000));
            p = next;
            t += 900;
        }
        bars.add(closer.apply(t, p));
        return bars;
    }

    private static Signal reversalOf(List<Bar> bars, IntradayConfig cfg) {
        return IntradayScanner.scan("T.NS", "Test", "NIFTY_500", bars, cfg)[1];
    }

    private static Signal bullishOf(List<Bar> bars, IntradayConfig cfg) {
        return IntradayScanner.scan("T.NS", "Test", "NIFTY_500", bars, cfg)[0];
    }

    // ---------------------------------------------------------------- reversal

    @Test
    void aShootingStarAboveTheEmaWithRsiOver65TriggersTheReversal() {
        // small body at the low of the candle, long upper wick
        List<Bar> bars = rallyThen((t, p) -> bar(t, p, p * 1.03, p * 0.999, p * 1.002, 250_000));

        Signal s = reversalOf(bars, cfg);

        assertNotNull(s, "the specified setup must trigger");
        assertEquals("REVERSAL", s.side());
        assertEquals("SHOOTING_STAR", s.pattern().type());
        assertTrue(s.rsi() > cfg.getReversalRsiMin(), "RSI was " + s.rsi());
        assertTrue(s.price() > s.ema(), "price must be above the EMA");
        assertTrue(s.score() > 0 && s.score() <= 10);
        assertTrue(s.explanation().toLowerCase().contains("shooting star"), s.explanation());
    }

    @Test
    void theSameCandleBelowTheEmaDoesNotTrigger() {
        // A decline leaves price under its EMA, so the rejection candle has nothing to reject.
        List<Bar> bars = new ArrayList<>();
        double p = 100;
        long t = SESSION_OPEN;
        for (int i = 0; i < 60; i++) {
            double next = p * 0.996;
            bars.add(bar(t, p, Math.max(p, next) * 1.001, Math.min(p, next) * 0.999, next, 100_000));
            p = next;
            t += 900;
        }
        bars.add(bar(t, p, p * 1.03, p * 0.999, p * 1.002, 250_000));

        assertNull(reversalOf(bars, cfg), "price below the EMA must not produce a reversal signal");
    }

    @Test
    void raisingTheRsiFloorAboveTheReadingSuppressesTheSignal() {
        List<Bar> bars = rallyThen((t, p) -> bar(t, p, p * 1.03, p * 0.999, p * 1.002, 250_000));
        assertNotNull(reversalOf(bars, cfg));

        IntradayConfig strict = new IntradayConfig();
        strict.setReversalRsiMin(95);
        assertNull(reversalOf(bars, strict), "the RSI floor is configuration and must gate the result");
    }

    @Test
    void anOrdinaryCandleAfterTheSameRallyDoesNotTrigger() {
        // Everything else identical; only the candle shape is unremarkable.
        List<Bar> bars = rallyThen((t, p) -> bar(t, p, p * 1.004, p * 0.997, p * 1.002, 250_000));

        assertNull(reversalOf(bars, cfg), "no bearish pattern means no signal");
    }

    @Test
    void aMoreStretchedStockScoresHigherThanALessStretchedOne() {
        List<Bar> heavy = rallyThen((t, p) -> bar(t, p, p * 1.03, p * 0.999, p * 1.002, 400_000));
        List<Bar> thin = rallyThen((t, p) -> bar(t, p, p * 1.03, p * 0.999, p * 1.002, 40_000));

        Signal a = reversalOf(heavy, cfg);
        Signal b = reversalOf(thin, cfg);

        assertNotNull(a);
        assertNotNull(b);
        assertTrue(a.score() > b.score(), "volume must separate them: " + a.score() + " vs " + b.score());
    }

    // ---------------------------------------------------------------- bullish

    /**
     * A climb gentle enough to keep RSI inside the 55-70 band, finishing with a bullish engulfing
     * candle on heavy volume above VWAP.
     */
    private static List<Bar> gentleClimbThenEngulfing(double closingVolume) {
        List<Bar> bars = new ArrayList<>();
        double p = 100;
        long t = SESSION_OPEN;
        // alternating up/down drift with a slight upward bias keeps RSI mid-band
        for (int i = 0; i < 58; i++) {
            double next = i % 3 == 2 ? p * 0.997 : p * 1.0025;
            bars.add(bar(t, p, Math.max(p, next) * 1.001, Math.min(p, next) * 0.999, next, 100_000));
            p = next;
            t += 900;
        }
        double down = p * 0.995;
        bars.add(bar(t, p, p * 1.001, down * 0.999, down, 100_000));
        t += 900;
        bars.add(bar(t, down, p * 1.008, down * 0.999, p * 1.006, closingVolume));
        return bars;
    }

    @Test
    void aBullishCandleAboveEmaAndVwapInTheRsiBandOnVolumeTriggers() {
        Signal s = bullishOf(gentleClimbThenEngulfing(300_000), cfg);

        assertNotNull(s, "the bullish setup must trigger");
        assertEquals("BULLISH", s.side());
        assertEquals("BULLISH_ENGULFING", s.pattern().type());
        assertTrue(s.rsi() >= cfg.getBullishRsiMin() && s.rsi() <= cfg.getBullishRsiMax(),
                "RSI " + s.rsi() + " must sit inside the band");
        assertTrue(s.price() > s.ema());
        assertTrue(s.price() > s.vwap(), "must be above VWAP");
        assertTrue(s.volumeRatio() >= cfg.getBullishVolumeRatio());
    }

    @Test
    void thinVolumeOnTheSignalCandleBlocksTheBullishSetup() {
        assertNull(bullishOf(gentleClimbThenEngulfing(50_000), cfg),
                "a bullish candle nobody participated in is not a signal");
    }

    @Test
    void anOverboughtStockIsExcludedFromTheBullishSideByDesign() {
        // The steep rally pushes RSI past the band's ceiling. This is the asymmetry that stops the
        // bullish scan from simply returning whatever has already run the furthest.
        List<Bar> bars = rallyThen((t, p) -> bar(t, p * 0.998, p * 1.01, p * 0.997, p * 1.008, 400_000));

        double rsi = com.javawarriors.breakout.breakout.BreakoutAnalyzer.rsi(
                bars.stream().mapToDouble(Bar::close).toArray(), cfg.getRsiPeriod())[bars.size() - 1];
        assertTrue(rsi > cfg.getBullishRsiMax(), "fixture must actually be overbought, RSI " + rsi);

        assertNull(bullishOf(bars, cfg), "RSI above the band must exclude the stock from the bullish list");
    }

    @Test
    void vwapResetsPerSessionRatherThanRunningAcrossDays() {
        List<Bar> bars = new ArrayList<>();
        // yesterday, traded far away from today's prices
        long yesterday = SESSION_OPEN - 86_400L;
        for (int i = 0; i < 10; i++) {
            bars.add(bar(yesterday + i * 900L, 50, 50.5, 49.5, 50, 1_000_000));
        }
        for (int i = 0; i < 10; i++) {
            bars.add(bar(SESSION_OPEN + i * 900L, 100, 100.5, 99.5, 100, 100_000));
        }

        double vwap = IntradayScanner.sessionVwap(bars, bars.size() - 1);

        assertTrue(vwap > 95 && vwap < 105,
                "VWAP must reflect today only, got " + vwap + " (yesterday traded near 50)");
    }

    // ---------------------------------------------------------------- shared

    @Test
    void tooLittleHistoryProducesNoSignalOnEitherSide() {
        List<Bar> bars = new ArrayList<>();
        for (int i = 0; i < 5; i++) bars.add(bar(SESSION_OPEN + i * 900L, 100, 101, 99, 100, 100_000));

        Signal[] out = IntradayScanner.scan("T.NS", "Test", "NIFTY_500", bars, cfg);

        assertNull(out[0]);
        assertNull(out[1]);
    }

    /**
     * Regression: the history window used to be one fixed value for every interval. Five sessions
     * is 375 candles at 5m and 125 at 15m, but only ~35 at 60m — under the minimum-bars floor, so
     * a 60m scan skipped all 504 symbols and returned nothing at all, silently. Every offered
     * interval has to come back with more candles than the floor requires.
     */
    @Test
    void everyOfferedIntervalHasAWindowThatClearsTheMinimumBarCount() {
        Map<String, Integer> candlesPerSession = Map.of("5m", 75, "15m", 25, "60m", 7);
        Map<String, Integer> sessionsInRange = Map.of("5d", 5, "1mo", 20, "3mo", 60);

        for (String interval : cfg.getAllowedIntervals()) {
            String range = cfg.rangeFor(interval);
            assertTrue(sessionsInRange.containsKey(range),
                    interval + " maps to an unrecognised range " + range);
            int candles = candlesPerSession.get(interval) * sessionsInRange.get(range);
            assertTrue(candles > cfg.getMinBars(),
                    interval + " over " + range + " yields about " + candles
                            + " candles, which does not clear the " + cfg.getMinBars() + "-bar floor");
        }
    }

    @Test
    void aSignalCarriesEverythingTheTableRenders() {
        Signal s = reversalOf(rallyThen((t, p) -> bar(t, p, p * 1.03, p * 0.999, p * 1.002, 250_000)), cfg);
        var row = s.toRow();

        for (String k : new String[] {"symbol", "name", "side", "price", "changePct", "ema", "rsi",
                "vwap", "volumeRatio", "signalTime", "score", "explanation", "pattern",
                "patternType", "patternName"}) {
            assertTrue(row.containsKey(k), "row is missing " + k);
        }
    }
}
