package com.javawarriors.breakout.bullish;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * The Bullish Stocks tab's API.
 *
 * <p>Follows the conventions the rest of this app already uses: everything under {@code /api},
 * a POST to start a long-running scan that returns 202 immediately, a status endpoint to poll, and
 * a GET that serves the last completed payload or 404 when there is none yet. That is the same
 * contract {@code /api/scan} + {@code /api/results} offers, so the frontend's polling loop is
 * identical for both tabs.
 */
@RestController
@RequestMapping("/api/bullish-stocks")
public class BullishStocksController {

    private final BullishStocksService service;

    public BullishStocksController(BullishStocksService service) {
        this.service = service;
    }

    /** The ranked Nifty 500 list from the last completed run. */
    @GetMapping
    public ResponseEntity<Map<String, Object>> results() {
        Map<String, Object> results = service.results();
        return results == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(results);
    }

    /** Starts a background ranking run. 202 when started, 409 when one is already going. */
    @PostMapping("/scan")
    public ResponseEntity<Map<String, String>> scan() {
        return service.triggerScan()
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "already-running"));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    /**
     * Live assessment of one symbol, e.g. {@code GET /api/bullish-stocks/TATAELXSI.NS}. Works for
     * any listed symbol, not only Nifty 500 members.
     */
    @GetMapping("/{symbol}")
    public ResponseEntity<?> one(@PathVariable String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing symbol"));
        }
        try {
            return ResponseEntity.ok(service.analyzeOne(symbol));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(Map.of("error", String.valueOf(e.getMessage())));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "fetch failed for " + symbol + ": " + e.getMessage()));
        }
    }
}
