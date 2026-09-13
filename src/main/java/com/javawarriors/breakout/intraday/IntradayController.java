package com.javawarriors.breakout.intraday;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The Intraday Scanner's API.
 *
 * <p>Follows the same contract as the other scans - POST to start, poll status, GET the last
 * completed payload or 404 - so the frontend reuses one polling loop for all three.
 *
 * <p>One payload carries both sides. The scan fetches each symbol once and evaluates the bullish
 * and reversal strategies on the same candles, so splitting them across two endpoints would double
 * the work to answer the same question.
 */
@RestController
@RequestMapping("/api/intraday")
public class IntradayController {

    private final IntradayService service;
    private final IntradayConfig cfg;

    public IntradayController(IntradayService service, IntradayConfig cfg) {
        this.service = service;
        this.cfg = cfg;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> results() {
        Map<String, Object> results = service.results();
        return results == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(results);
    }

    /** {@code POST /api/intraday/scan?interval=15m} — 202 when started, 409 when already running. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> scan(
            @RequestParam(value = "interval", required = false) String interval) {
        if (interval != null && !cfg.getAllowedIntervals().contains(interval)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unsupported ?interval=" + interval + " (allowed: " + cfg.getAllowedIntervals() + ")"));
        }
        return service.triggerScan(interval)
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "already-running"));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }
}
