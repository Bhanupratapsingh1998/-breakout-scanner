package com.javawarriors.breakout.intraday;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.intraday.CandlePatternDetector.CandlePattern;
import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two intraday strategies, evaluated on the most recent completed candle.
 *
 * <p><b>Reversal (bearish, from a top)</b> — the specified strategy: price above the 20 EMA, RSI
 * over 65, and a bearish reversal candle. Read together those say a stock has run, is overbought,
 * and has just printed a rejection. The Shooting Star is the canonical candle for it; the scan
 * accepts the whole bearish vocabulary so the pattern filter has something to filter, and reports
 * which one fired.
 *
 * <p><b>Bullish</b> — the mirror, with one deliberate asymmetry. Its RSI is a band (55-70), not a
 * floor, because a floor would return whatever had already run the furthest, which is the failure
 * mode the rest of this app is built to avoid. It also requires price above VWAP and an expansion
 * in volume, so the signal is a move being participated in rather than a drift.
 *
 * <p>VWAP is computed per session, resetting each trading day in exchange-local time. It is the
 * benchmark intraday money is measured against, and on a 15-minute candle it is far more
 * informative than another moving average would be.
 */
public final class IntradayScanner {

    /** NSE session boundaries are dates in exchange-local time, not UTC. */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    public record Signal(String symbol, String name, String universe, String side,
                         double price, double previousClose, double changePct,
                         double ema, double rsi, double vwap, double volumeRatio,
                         double distanceFromEmaPct, double distanceFromVwapPct,
                         long signalTime, CandlePattern pattern, double score, String explanation) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("symbol", symbol);
            row.put("name", name);
            row.put("universe", universe);
            row.put("side", side);
            row.put("price", price);
            row.put("previousClose", previousClose);
            row.put("changePct", changePct);
            row.put("ema", ema);
            row.put("rsi", rsi);
            row.put("vwap", vwap);
            row.put("volumeRatio", volumeRatio);
            row.put("distanceFromEmaPct", distanceFromEmaPct);
            row.put("distanceFromVwapPct", distanceFromVwapPct);
            row.put("signalTime", signalTime);
            row.put("score", score);
            row.put("explanation", explanation);
            row.put("pattern", pattern.toRow());
            row.put("patternType", pattern.type());
            row.put("patternName", pattern.name());
            return row;
        }
    }

    private IntradayScanner() {
    }

    /**
     * Evaluates both strategies on {@code bars}. Returns null entries when a side does not trigger.
     *
     * @return {@code [bullishOrNull, reversalOrNull]}
     */
    public static Signal[] scan(String symbol, String name, String universe, List<Bar> bars,
                                IntradayConfig cfg) {
        if (bars == null || bars.size() < cfg.getMinBars()) return new Signal[] {null, null};

        int n = bars.size();
        int i = n - 1;                       // the latest completed candle
        Bar c = bars.get(i);
        if (!(c.close() > 0)) return new Signal[] {null, null};

        double[] close = new double[n];
        double[] high = new double[n];
        double[] low = new double[n];
        for (int k = 0; k < n; k++) {
            close[k] = bars.get(k).close();
            high[k] = bars.get(k).high();
            low[k] = bars.get(k).low();
        }

        double ema = BreakoutAnalyzer.ema(close, cfg.getEmaPeriod())[i];
        double rsi = BreakoutAnalyzer.rsi(close, cfg.getRsiPeriod())[i];
        double vwap = sessionVwap(bars, i);
        double avgVolume = averageVolume(bars, i, cfg.getVolumeLookback());
        double volumeRatio = avgVolume > 0 ? c.volume() / avgVolume : 0;
        double prevClose = bars.get(i - 1).close();
        double changePct = prevClose > 0 ? (c.close() / prevClose - 1) * 100 : Double.NaN;
        double fromEma = ema > 0 ? (c.close() / ema - 1) * 100 : Double.NaN;
        double fromVwap = vwap > 0 ? (c.close() / vwap - 1) * 100 : Double.NaN;

        Signal bullish = null;
        Signal reversal = null;

        // ---- Reversal: above the 20 EMA, overbought, and a bearish rejection candle.
        CandlePattern bear = CandlePatternDetector.strongest(bars, i, Direction.BEARISH);
        if (c.close() > ema && rsi > cfg.getReversalRsiMin() && bear != null) {
            double score = reversalScore(bear, rsi, volumeRatio, fromEma, fromVwap, cfg);
            reversal = new Signal(symbol, name, universe, "REVERSAL", c.close(), prevClose, changePct,
                    ema, rsi, vwap, volumeRatio, fromEma, fromVwap, c.time(), bear, score,
                    String.format("Trading %.1f%% above its %d EMA with RSI %.0f, and has just printed"
                                    + " a %s. %s",
                            fromEma, cfg.getEmaPeriod(), rsi, bear.name().toLowerCase(), bear.description()));
        }

        // ---- Bullish: above the EMA and VWAP, strong but not overbought, on expanding volume.
        CandlePattern bull = CandlePatternDetector.strongest(bars, i, Direction.BULLISH);
        boolean rsiInBand = rsi >= cfg.getBullishRsiMin() && rsi <= cfg.getBullishRsiMax();
        if (c.close() > ema && vwap > 0 && c.close() > vwap && rsiInBand
                && volumeRatio >= cfg.getBullishVolumeRatio() && bull != null) {
            double score = bullishScore(bull, rsi, volumeRatio, fromVwap, cfg);
            bullish = new Signal(symbol, name, universe, "BULLISH", c.close(), prevClose, changePct,
                    ema, rsi, vwap, volumeRatio, fromEma, fromVwap, c.time(), bull, score,
                    String.format("Holding above its %d EMA and %.1f%% above VWAP with RSI %.0f, on"
                                    + " %.1fx average volume, and has just printed a %s. %s",
                            cfg.getEmaPeriod(), fromVwap, rsi, volumeRatio, bull.name().toLowerCase(),
                            bull.description()));
        }

        return new Signal[] {bullish, reversal};
    }

    /**
     * Signal quality out of 10. Pattern strength carries the most weight because the candle is the
     * trigger; the rest describes how good the conditions around it are.
     */
    private static double reversalScore(CandlePattern p, double rsi, double volumeRatio,
                                        double fromEma, double fromVwap, IntradayConfig cfg) {
        double score = p.strength() * 1.5;                                  // 1.5 - 4.5
        score += rsi >= 80 ? 3 : rsi >= 72 ? 2.2 : rsi >= 68 ? 1.4 : 0.8;   // more stretched, more to give back
        score += volumeRatio >= 2 ? 2 : volumeRatio >= 1.5 ? 1.4 : volumeRatio >= 1 ? 0.7 : 0;
        if (!Double.isNaN(fromEma) && fromEma >= 2) score += 0.5;           // genuinely extended
        if (!Double.isNaN(fromVwap) && fromVwap >= 1) score += 0.5;         // stretched from the session benchmark
        return Math.min(10, Math.round(score * 10) / 10.0);
    }

    private static double bullishScore(CandlePattern p, double rsi, double volumeRatio,
                                       double fromVwap, IntradayConfig cfg) {
        double score = p.strength() * 1.5;
        score += rsi >= 60 && rsi <= 68 ? 2.5 : 1.5;                        // the middle of the band is best
        score += volumeRatio >= 3 ? 3 : volumeRatio >= 2 ? 2.3 : 1.5;
        // Just above VWAP is the good entry; far above it is the chase this band exists to avoid.
        if (!Double.isNaN(fromVwap)) score += fromVwap <= 1.5 ? 1 : fromVwap <= 3 ? 0.5 : 0;
        return Math.min(10, Math.round(score * 10) / 10.0);
    }

    /**
     * Volume-weighted average price for the session bar {@code i} belongs to.
     *
     * <p>Reset per session on purpose: VWAP carried across days is not a level anyone trades
     * against. Typical price (H+L+C)/3 is the standard weighting input.
     */
    static double sessionVwap(List<Bar> bars, int i) {
        LocalDate session = sessionOf(bars.get(i));
        double pv = 0, vol = 0;
        for (int k = i; k >= 0; k--) {
            if (!sessionOf(bars.get(k)).equals(session)) break;
            Bar b = bars.get(k);
            double typical = (b.high() + b.low() + b.close()) / 3.0;
            pv += typical * b.volume();
            vol += b.volume();
        }
        return vol > 0 ? pv / vol : Double.NaN;
    }

    private static LocalDate sessionOf(Bar b) {
        return Instant.ofEpochSecond(b.time()).atZone(MARKET_ZONE).toLocalDate();
    }

    private static double averageVolume(List<Bar> bars, int i, int lookback) {
        int from = Math.max(0, i - lookback);
        if (i <= from) return 0;
        double sum = 0;
        for (int k = from; k < i; k++) sum += bars.get(k).volume();
        return sum / (i - from);
    }
}
