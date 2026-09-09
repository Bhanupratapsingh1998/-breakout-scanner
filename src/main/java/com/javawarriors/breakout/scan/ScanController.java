package com.javawarriors.breakout.scan;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ScanController {

    private final ScanService scanService;

    public ScanController(ScanService scanService) {
        this.scanService = scanService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    /** Live single-symbol lookup, e.g. GET /api/analyze?symbol=TATAELXSI.NS */
    @GetMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestParam(value = "symbol", required = false) String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing ?symbol="));
        }
        try {
            return ResponseEntity.ok(scanService.analyze(symbol));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "fetch failed for " + symbol + ": " + e.getMessage()));
        }
    }

    /** Kicks off a full Nifty 50 + Next 50 + Nifty 500 rescan in the background. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> triggerScan() {
        boolean started = scanService.triggerScan();
        if (!started) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "already-running"));
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"));
    }

    @GetMapping("/scan/status")
    public Map<String, Object> scanStatus() {
        return scanService.status();
    }

    /** Last completed scan's results — what the dashboard renders. */
    @GetMapping("/results")
    public ResponseEntity<Map<String, Object>> results() {
        Map<String, Object> results = scanService.results();
        if (results == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(results);
    }
}
