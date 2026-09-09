package com.javawarriors.breakout.scan;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.breakout.BreakoutResult;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.nearbreakout.NearBreakoutAnalyzer;
import com.javawarriors.breakout.nearbreakout.NearBreakoutResult;
import com.javawarriors.breakout.tradesetup.TradeSetupAnalyzer;
import com.javawarriors.breakout.tradesetup.TradeSetupResult;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Holds scan state (running/progress/last results) and the single-symbol lookup used by the dashboard. */
@Service
public class ScanService {

    private final BreakoutAnalyzer analyzer = new BreakoutAnalyzer();
    private final TradeSetupAnalyzer tradeSetupAnalyzer = new TradeSetupAnalyzer();
    private final NearBreakoutAnalyzer nearBreakoutAnalyzer = new NearBreakoutAnalyzer();
    private final YahooDataSource source = new YahooDataSource();

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicReference<String> scanProgress = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastScanSummary = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastResults = new AtomicReference<>(null);

    /** Kicks off a full rescan in the background if one isn't already running. Returns false if one is. */
    public boolean triggerScan() {
        if (!scanRunning.compareAndSet(false, true)) {
            return false;
        }
        Thread worker = new Thread(() -> {
            try {
                ScanRunner.ScanOutcome outcome = ScanRunner.runFullScan(scanProgress::set);
                Map<String, Object> payload = ScanRunner.buildPayload(outcome.results(), outcome.reversals(),
                        outcome.nearBreakouts(), outcome.universeSize(), outcome.nifty500Names());
                lastResults.set(payload);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("scanned", outcome.results().size());
                summary.put("failed", outcome.failed());
                summary.put("universeSize", outcome.universeSize());
                summary.put("generatedAt", payload.get("generatedAt"));
                lastScanSummary.set(summary);
            } catch (Exception e) {
                lastScanSummary.set(Map.of("error", String.valueOf(e.getMessage())));
            } finally {
                scanProgress.set(null);
                scanRunning.set(false);
            }
        }, "scan-runner");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", scanRunning.get());
        body.put("progress", scanProgress.get());
        body.put("lastResult", lastScanSummary.get());
        return body;
    }

    /** Last completed scan payload, or null if no scan has finished yet. */
    public Map<String, Object> results() {
        return lastResults.get();
    }

    /** Live single-symbol lookup for the dashboard's "custom stock" search. */
    public Map<String, Object> analyze(String rawSymbol) throws Exception {
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS"; // default to NSE if no exchange suffix given

        List<Bar> bars = source.fetchDaily(symbol, "18mo");
        if (bars.size() < 200) {
            throw new IllegalArgumentException(symbol + ": need >= 200 bars of history, got " + bars.size());
        }

        String universe = universeOf(symbol);
        // stocks outside all three benchmarked tiers still get compared against the broad
        // Nifty 500 as a general market yardstick, rather than skipping the check entirely
        String benchmarkUniverse = "CUSTOM".equals(universe) ? "NIFTY_500" : universe;
        List<Bar> benchmarkBars = BenchmarkSource.barsFor(benchmarkUniverse);
        if (benchmarkBars == null) {
            BenchmarkSource.refreshAll();
            benchmarkBars = BenchmarkSource.barsFor(benchmarkUniverse);
        }

        BreakoutResult r = analyzer.analyze(symbol, bars, benchmarkBars, BenchmarkSource.displayName(benchmarkUniverse));
        Map<String, Object> row = toRow(r, universe);
        String name = row.get("name").toString();

        // Candlestick confirmation layer for both setup types — reuses the same fetched bars.
        TradeSetupResult breakoutSetup = tradeSetupAnalyzer.scoreBreakoutSetup(
                symbol, bars, benchmarkBars, BenchmarkSource.displayName(benchmarkUniverse));
        row.put("breakoutSetup", breakoutSetup.toRow(name, universe));

        TradeSetupResult reversalSetup = tradeSetupAnalyzer.scoreReversalSetup(symbol, bars);
        if (!"REJECTED".equals(reversalSetup.classification)) {
            row.put("reversalSetup", reversalSetup.toRow(name, universe));
        }

        NearBreakoutResult nearBreakout = nearBreakoutAnalyzer.analyze(symbol, bars);
        if (nearBreakout != null) row.put("nearBreakout", nearBreakout.toRow(name, universe));

        return row;
    }

    private String universeOf(String symbol) {
        if (NiftyUniverse.NIFTY_50.contains(symbol)) return "NIFTY_50";
        if (NiftyUniverse.NIFTY_NEXT_50.contains(symbol)) return "NEXT_50";
        return NseIndexSource.cached().containsKey(symbol) ? "NIFTY_500" : "CUSTOM";
    }

    private Map<String, Object> toRow(BreakoutResult r, String universe) {
        String fallbackName = r.symbol.replaceAll("\\.(NS|BO)$", "");
        Map<String, String> nifty500 = NseIndexSource.cached();
        String name = nifty500.getOrDefault(r.symbol, NiftyUniverse.NAMES.getOrDefault(r.symbol, fallbackName));
        return r.toRow(name, universe);
    }
}
