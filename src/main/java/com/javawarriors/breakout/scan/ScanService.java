package com.javawarriors.breakout.scan;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Holds the reversal scan's state: running/progress/last results.
 *
 * <p>Previously this also served a single-symbol breakout lookup for the watchlist. That analysis
 * went with the Breakout Scanner; single-symbol lookups are now served by
 * {@code GET /api/bullish-stocks/{symbol}}, which returns a far more complete assessment of the
 * same stock.
 */
@Service
public class ScanService {

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
                Map<String, Object> payload = ScanRunner.buildPayload(outcome.reversals(),
                        outcome.universeSize(), outcome.nifty500Names());
                lastResults.set(payload);

                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("scanned", outcome.universeSize() - outcome.failed());
                summary.put("reversals", outcome.reversals().size());
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
}
