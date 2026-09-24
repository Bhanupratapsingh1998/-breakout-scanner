package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.index500.SectorService;
import com.javawarriors.breakout.index500.StockMetadata;
import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import java.util.function.Consumer;

/**
 * Runs the wick-reversal scan across the Nifty 500 and keeps the result.
 *
 * <p>One interval per run, because the merge is defined against a single candle size and a list
 * mixing 30-minute and 2-day signals would be ranking two different things against each other. The
 * interval the last run used travels with the result so the UI can say which clock it is showing.
 * Direction and group size are not like that: they are read off the same bars in the same pass, so
 * both are always detected and the UI filters between them.
 *
 * <p>The daily interval is deliberately pointed at the same 18mo window the bullish ranking and the
 * Index 500 analysis use. Same cache key, so a daily run after either of those fetches nothing at
 * all; the intraday intervals have their own series and cannot share.
 *
 * <p>One symbol failing never stops the rest - it is logged with symbol and timestamp, counted, and
 * the scan moves on.
 */
@Service
public class WickReversalService {

    private static final Logger log = LoggerFactory.getLogger(WickReversalService.class);

    private final WickReversalConfig cfg;
    private final SectorService sectors;
    private final WickReversalDetector detector;
    private final YahooDataSource source = new YahooDataSource();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> progress = new AtomicReference<>(null);
    private final AtomicReference<List<WickSignal>> lastRun = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastSummary = new AtomicReference<>(null);
    private final AtomicReference<String> generatedAt = new AtomicReference<>(null);
    private final AtomicReference<String> lastInterval = new AtomicReference<>(null);

    public WickReversalService(WickReversalConfig cfg, SectorService sectors,
                               WickReversalDetector detector) {
        this.cfg = cfg;
        this.sectors = sectors;
        this.detector = detector;
    }

    public boolean triggerScan(String requested) {
        String interval = requested == null || requested.isBlank()
                ? cfg.getDefaultInterval() : requested.trim();
        if (!running.compareAndSet(false, true)) return false;

        Thread worker = new Thread(() -> {
            try {
                Summary result = runScan(interval, progress::set);
                lastRun.set(result.signals());
                lastInterval.set(interval);
                generatedAt.set(Instant.now().toString());

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("interval", interval);
                summary.put("candleCounts", cfg.getCandleCounts());
                summary.put("directions", cfg.getDirections());
                summary.put("scanned", result.scanned());
                summary.put("failed", result.failed());
                summary.put("signals", result.signals().size());
                summary.put("generatedAt", generatedAt.get());
                lastSummary.set(summary);
            } catch (Exception e) {
                log.error("Wick reversal scan failed", e);
                lastSummary.set(Map.of("error", String.valueOf(e.getMessage())));
            } finally {
                progress.set(null);
                running.set(false);
            }
        }, "wick-scan-runner");
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

    public List<WickSignal> lastRun() {
        return lastRun.get();
    }

    public String generatedAt() {
        return generatedAt.get();
    }

    public String lastInterval() {
        return lastInterval.get();
    }

    /** Signals found, plus how much of the universe actually produced usable bars. */
    record Summary(List<WickSignal> signals, int scanned, int failed) {
    }

    Summary runScan(String interval, Consumer<String> onProgress) {
        try {
            NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            log.warn("Nifty 500 list unavailable, using cached membership: {}", e.getMessage());
        }

        String range = cfg.rangeFor(interval);
        List<StockMetadata> universe = sectors.universe();
        List<WickSignal> found = new ArrayList<>();
        int scanned = 0;
        int failed = 0;

        for (int i = 0; i < universe.size(); i++) {
            StockMetadata meta = universe.get(i);
            if (onProgress != null) {
                onProgress.accept(meta.symbol() + " (" + (i + 1) + "/" + universe.size() + ")");
            }
            boolean cached = BarCache.peek(meta.symbol(), range, interval) != null;
            try {
                List<Bar> bars = BarCache.series(source, meta.symbol(), range, interval);
                for (WickSignal signal : detector.detectAll(meta.symbol(), meta.companyName(),
                        meta.sector(), interval, bars, cfg)) {
                    if (signal.score() >= cfg.getMinScore()) found.add(signal);
                }
                scanned++;
            } catch (Exception e) {
                failed++;
                log.warn("Wick scan: skipping {} at {} - {}", meta.symbol(), Instant.now(), e.toString());
            }
            if (!cached) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        return new Summary(capPerGroupSize(found, cfg), scanned, failed);
    }

    /**
     * Caps each direction-and-group-size bucket separately, then interleaves them into one list.
     *
     * <p>A single global cap would quietly starve whichever bucket produced more signals - and
     * worse, it would shrink the bullish two-candle view simply because bearish or three-candle
     * groups exist, which is a filter changing results it has no business touching. Each bucket
     * gets its own budget, so selecting one shows as full a list as it would have if none of the
     * others were ever detected.
     */
    static List<WickSignal> capPerGroupSize(List<WickSignal> found, WickReversalConfig cfg) {
        Map<String, List<WickSignal>> bySize = new LinkedHashMap<>();
        for (WickSignal s : found) {
            bySize.computeIfAbsent(s.direction() + "/" + s.candles(), k -> new ArrayList<>()).add(s);
        }

        List<WickSignal> out = new ArrayList<>();
        for (List<WickSignal> group : bySize.values()) {
            group.sort(ranking());
            out.addAll(group.subList(0, Math.min(group.size(), cfg.getMaxResults())));
        }
        out.sort(ranking());
        return out;
    }

    /**
     * Freshest first, then best.
     *
     * <p>Recency leads because this is a level-based setup: a signal from twelve candles ago has had
     * twelve candles to be taken out, and a high score does not make an old level a current one.
     * Within the same candle the score decides, and the smaller group breaks a remaining tie - a
     * rejection that needed fewer candles to complete is the tighter reading of the same extreme.
     */
    static Comparator<WickSignal> ranking() {
        return Comparator.comparingInt(WickSignal::barsAgo)
                .thenComparing(Comparator.comparingDouble(WickSignal::score).reversed())
                .thenComparingInt(WickSignal::candles);
    }
}
