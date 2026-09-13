package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.scan.ScanRunner;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Owns the Nifty 500 bullish ranking: the background scan, the last completed payload, and the
 * live single-symbol lookup.
 *
 * <p>Deliberately shaped like the existing {@code ScanService} - same background-thread-plus-
 * progress-string model, same "last completed payload" semantics - so the two tabs behave
 * identically from the frontend's point of view and there is one pattern to understand rather than
 * two.
 *
 * <p>The universe is the live Nifty 500 constituent list from NSE, with the curated Nifty 100
 * folded in ahead of it exactly as {@link ScanRunner#buildWatchlist} already does, so a temporary
 * NSE outage degrades to the top 100 rather than to nothing.
 */
@Service
public class BullishStocksService {

    /** Same window the breakout scan fetches, so the two scans share cache entries. */
    private static final String RANGE = "18mo";

    private final BullishConfig cfg;
    private final YahooDataSource source = new YahooDataSource();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> progress = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastPayload = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastSummary = new AtomicReference<>(null);

    public BullishStocksService(BullishConfig cfg) {
        this.cfg = cfg;
    }

    /** Starts a background ranking run, or returns false if one is already going. */
    public boolean triggerScan() {
        if (!running.compareAndSet(false, true)) return false;

        Thread worker = new Thread(() -> {
            try {
                Map<String, Object> payload = runScan(progress::set);
                lastPayload.set(payload);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("analyzedStockCount", payload.get("analyzedStockCount"));
                summary.put("bullishStockCount", payload.get("bullishStockCount"));
                summary.put("failed", payload.get("failedCount"));
                summary.put("generatedAt", payload.get("timestamp"));
                lastSummary.set(summary);
            } catch (Exception e) {
                lastSummary.set(Map.of("error", String.valueOf(e.getMessage())));
            } finally {
                progress.set(null);
                running.set(false);
            }
        }, "bullish-scan-runner");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running.get());
        body.put("progress", progress.get());
        body.put("lastResult", lastSummary.get());
        body.put("cachedSeries", BarCache.size());
        return body;
    }

    /** Last completed ranking, or null when none has finished in this process yet. */
    public Map<String, Object> results() {
        return lastPayload.get();
    }

    /** Live single-symbol assessment, for the detail view and for symbols outside the index. */
    public Map<String, Object> analyzeOne(String rawSymbol) throws IOException {
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        ensureBenchmarks();
        List<Bar> nifty50 = BenchmarkSource.barsFor("NIFTY_50");
        List<Bar> nifty500 = BenchmarkSource.barsFor("NIFTY_500");
        MarketRegimeAnalyzer.Regime regime = MarketRegimeAnalyzer.analyze(nifty50, nifty500, cfg);

        List<Bar> bars = BarCache.daily(source, symbol, RANGE);
        Map<String, String> names = NseIndexSource.cached();

        BullishStockResult result = new BullishStockAnalyzer(cfg).analyze(
                symbol, displayName(symbol, names), ScanRunner.universeOf(symbol),
                NseIndexSource.sectorOf(symbol), bars, nifty50, nifty500, regime);

        Map<String, Object> row = result.toRow();
        row.put("marketRegime", regime.toRow());
        return row;
    }

    /** The full ranking run. Package-private so the controller never calls it off the request thread. */
    Map<String, Object> runScan(java.util.function.Consumer<String> onProgress) {
        Map<String, String> names;
        try {
            names = NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            names = NseIndexSource.cached();   // degrade to the curated 100 + whatever was cached
        }
        BenchmarkSource.refreshAll();

        List<Bar> nifty50 = BenchmarkSource.barsFor("NIFTY_50");
        List<Bar> nifty500 = BenchmarkSource.barsFor("NIFTY_500");
        MarketRegimeAnalyzer.Regime regime = MarketRegimeAnalyzer.analyze(nifty50, nifty500, cfg);

        List<String> watchlist = ScanRunner.buildWatchlist(names);
        BullishStockAnalyzer analyzer = new BullishStockAnalyzer(cfg);
        List<BullishStockResult> results = new ArrayList<>();
        int failed = 0;

        for (int i = 0; i < watchlist.size(); i++) {
            String symbol = watchlist.get(i);
            if (onProgress != null) onProgress.accept(symbol + " (" + (i + 1) + "/" + watchlist.size() + ")");

            boolean servedFromCache = BarCache.peek(symbol, RANGE) != null;
            try {
                List<Bar> bars = BarCache.daily(source, symbol, RANGE);
                results.add(analyzer.analyze(symbol, displayName(symbol, names),
                        ScanRunner.universeOf(symbol), NseIndexSource.sectorOf(symbol),
                        bars, nifty50, nifty500, regime));
            } catch (Exception e) {
                failed++;
            }
            if (!servedFromCache) {
                try {
                    Thread.sleep(150);   // only when this symbol actually hit the network
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        results.sort(BullishStockResult.BY_RANK);
        return buildPayload(results, regime, watchlist.size(), failed, cfg);
    }

    /** Assembles the API payload. Static and package-private so tests can build one without a scan. */
    static Map<String, Object> buildPayload(List<BullishStockResult> ranked,
                                            MarketRegimeAnalyzer.Regime regime,
                                            int universeSize, int failed, BullishConfig cfg) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (BullishStockResult r : ranked) {
            if (rows.size() >= cfg.getMaxResults()) break;
            Map<String, Object> row = r.toRow();
            row.put("rank", rank++);
            rows.add(row);
        }

        long bullish = ranked.stream().filter(r -> r.score().total() >= cfg.getScoreWatchlist()).count();
        long aPlus = ranked.stream().filter(r -> r.score().total() >= cfg.getScoreAPlus()).count();
        long breakouts = ranked.stream().filter(r -> r.breakout().confirmed() && !r.breakout().failed()).count();
        long pullbacks = ranked.stream()
                .filter(r -> PullbackAnalyzer.PULLBACK_OPPORTUNITY.equals(r.setup().stage())
                        || PullbackAnalyzer.BREAKOUT_RETEST.equals(r.setup().stage()))
                .count();
        long buyNow = ranked.stream()
                .filter(r -> PullbackAnalyzer.BUY_NOW.equals(r.setup().tradeStatus())).count();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("marketRegime", regime.toRow());
        payload.put("universeSize", universeSize);
        payload.put("analyzedStockCount", ranked.size());
        payload.put("failedCount", failed);
        payload.put("bullishStockCount", bullish);
        payload.put("aPlusCount", aPlus);
        payload.put("breakoutCount", breakouts);
        payload.put("pullbackCount", pullbacks);
        payload.put("buyNowCount", buyNow);
        payload.put("returnedCount", rows.size());
        payload.put("stocks", rows);
        return payload;
    }

    private void ensureBenchmarks() {
        if (BenchmarkSource.barsFor("NIFTY_500") == null && BenchmarkSource.barsFor("NIFTY_50") == null) {
            BenchmarkSource.refreshAll();
        }
    }

    private static String displayName(String symbol, Map<String, String> nifty500Names) {
        String fallback = symbol.replaceAll("\\.(NS|BO)$", "");
        return nifty500Names.getOrDefault(symbol, NiftyUniverse.NAMES.getOrDefault(symbol, fallback));
    }
}
