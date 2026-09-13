package com.javawarriors.breakout.intraday;

import com.javawarriors.breakout.intraday.IntradayScanner.Signal;
import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.scan.ScanRunner;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the intraday scan over the Nifty 500 and holds its result.
 *
 * <p>Same background-thread-and-progress shape as the other two scans, so the frontend polls it
 * identically. It does <em>not</em> share their cached bars: those are daily candles and this needs
 * 15-minute ones, which is a different series with a much shorter useful life — see
 * {@link BarCache}. One intraday scan therefore costs a full pass over the universe regardless of
 * what else has run, and the cache only helps a re-scan within the next few minutes.
 */
@Service
public class IntradayService {

    private final IntradayConfig cfg;
    private final YahooDataSource source = new YahooDataSource();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> progress = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastPayload = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastSummary = new AtomicReference<>(null);

    public IntradayService(IntradayConfig cfg) {
        this.cfg = cfg;
    }

    /** Starts a scan at the configured interval, or at {@code interval} when one is supplied. */
    public boolean triggerScan(String interval) {
        if (!running.compareAndSet(false, true)) return false;

        String chosen = interval != null && cfg.getAllowedIntervals().contains(interval)
                ? interval : cfg.getInterval();

        Thread worker = new Thread(() -> {
            try {
                Map<String, Object> payload = runScan(chosen, progress::set);
                lastPayload.set(payload);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("interval", chosen);
                summary.put("scanned", payload.get("analyzedCount"));
                summary.put("bullish", payload.get("bullishCount"));
                summary.put("reversal", payload.get("reversalCount"));
                summary.put("failed", payload.get("failedCount"));
                summary.put("generatedAt", payload.get("timestamp"));
                lastSummary.set(summary);
            } catch (Exception e) {
                lastSummary.set(Map.of("error", String.valueOf(e.getMessage())));
            } finally {
                progress.set(null);
                running.set(false);
            }
        }, "intraday-scan-runner");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running.get());
        body.put("progress", progress.get());
        body.put("lastResult", lastSummary.get());
        return body;
    }

    public Map<String, Object> results() {
        return lastPayload.get();
    }

    Map<String, Object> runScan(String interval, java.util.function.Consumer<String> onProgress) {
        Map<String, String> names;
        try {
            names = NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            names = NseIndexSource.cached();
        }

        List<String> watchlist = ScanRunner.buildWatchlist(names);
        List<Signal> bullish = new ArrayList<>();
        List<Signal> reversal = new ArrayList<>();
        int failed = 0;
        int analyzed = 0;
        long latestBar = 0;

        for (int i = 0; i < watchlist.size(); i++) {
            String symbol = watchlist.get(i);
            if (onProgress != null) onProgress.accept(symbol + " (" + (i + 1) + "/" + watchlist.size() + ")");

            // The window scales with the candle size — see IntradayConfig.ranges.
            String range = cfg.rangeFor(interval);
            boolean servedFromCache = BarCache.peek(symbol, range, interval) != null;
            try {
                List<Bar> bars = BarCache.series(source, symbol, range, interval);
                Signal[] out = IntradayScanner.scan(symbol, displayName(symbol, names),
                        ScanRunner.universeOf(symbol), bars, cfg);
                if (bars != null && bars.size() >= cfg.getMinBars()) {
                    analyzed++;
                    latestBar = Math.max(latestBar, bars.get(bars.size() - 1).time());
                } else {
                    failed++;
                }
                if (out[0] != null) bullish.add(out[0]);
                if (out[1] != null) reversal.add(out[1]);
            } catch (Exception e) {
                failed++;
            }
            if (!servedFromCache) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        Comparator<Signal> byScore = Comparator.comparingDouble(Signal::score).reversed();
        bullish.sort(byScore);
        reversal.sort(byScore);
        return buildPayload(bullish, reversal, interval, watchlist.size(), analyzed, failed, latestBar, cfg);
    }

    static Map<String, Object> buildPayload(List<Signal> bullish, List<Signal> reversal, String interval,
                                            int universeSize, int analyzed, int failed, long latestBar,
                                            IntradayConfig cfg) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("interval", interval);
        payload.put("universeSize", universeSize);
        payload.put("analyzedCount", analyzed);
        payload.put("failedCount", failed);
        payload.put("bullishCount", bullish.size());
        payload.put("reversalCount", reversal.size());
        // The candle the signals were read off. When the market is shut this is the last session's
        // close, and saying so is better than implying the scan is live.
        payload.put("latestCandleTime", latestBar);
        payload.put("bullish", rows(bullish, cfg.getMaxResults()));
        payload.put("reversal", rows(reversal, cfg.getMaxResults()));
        payload.put("bullishPatterns", CandlePatternDetector.BULLISH_TYPES);
        payload.put("reversalPatterns", CandlePatternDetector.BEARISH_TYPES);
        payload.put("strategy", Map.of(
                "bullish", String.format("Price > EMA%d and > VWAP, RSI(%d) between %.0f and %.0f,"
                                + " volume >= %.1fx average, and a bullish candle pattern.",
                        cfg.getEmaPeriod(), cfg.getRsiPeriod(), cfg.getBullishRsiMin(),
                        cfg.getBullishRsiMax(), cfg.getBullishVolumeRatio()),
                "reversal", String.format("Price > EMA%d, RSI(%d) > %.0f, and a bearish reversal"
                                + " candle such as a Shooting Star.",
                        cfg.getEmaPeriod(), cfg.getRsiPeriod(), cfg.getReversalRsiMin())));
        return payload;
    }

    private static List<Map<String, Object>> rows(List<Signal> signals, int cap) {
        List<Map<String, Object>> out = new ArrayList<>();
        int rank = 1;
        for (Signal s : signals) {
            if (out.size() >= cap) break;
            Map<String, Object> row = s.toRow();
            row.put("rank", rank++);
            out.add(row);
        }
        return out;
    }

    private static String displayName(String symbol, Map<String, String> nifty500Names) {
        String fallback = symbol.replaceAll("\\.(NS|BO)$", "");
        return nifty500Names.getOrDefault(symbol, NiftyUniverse.NAMES.getOrDefault(symbol, fallback));
    }
}
