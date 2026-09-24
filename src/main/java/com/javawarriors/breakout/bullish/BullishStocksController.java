package com.javawarriors.breakout.bullish;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * Single-symbol bullish scoring.
 *
 * <p>All that remains of the Bullish Stocks tab's API. The ranked-list, scan and status endpoints
 * went with the tab; this one stays because My Watchlist scores each followed stock through it.
 */
@RestController
@RequestMapping("/api/bullish-stocks")
public class BullishStocksController {

    /**
     * Sub-paths that were endpoints of the removed Bullish Stocks tab.
     *
     * <p>Deliberately excludes "backtest", which is still live on {@code BullishBacktestController}.
     * Spring routes that literal path to its own mapping ahead of this template, so it never
     * reaches here - but listing it would make this set a lie the moment that stopped being true.
     */
    private static final Set<String> RETIRED_PATHS = Set.of("scan", "status", "results");

    private final BullishStocksService service;

    public BullishStocksController(BullishStocksService service) {
        this.service = service;
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
        // "scan", "status" and the ranked list used to be endpoints here. Now that "/{symbol}" is
        // the only mapping left it swallows those paths, and a stale client polling /status would
        // otherwise send this off to fetch a stock called STATUS.NS - a live upstream request, every
        // 1.5 seconds, answered with a 502. They are gone, and 404 is what gone means.
        if (RETIRED_PATHS.contains(symbol.toLowerCase())) return ResponseEntity.notFound().build();
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
