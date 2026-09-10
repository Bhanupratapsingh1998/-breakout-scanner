package com.javawarriors.breakout.backtest;

import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.scan.ScanRunner;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runs the scanner's own entry condition back over a stock's history.
 *
 * <p>Answers the question the live scan cannot: when this setup appeared on THIS stock before,
 * what happened next. Several horizons are returned together because a signal that is reliably
 * good over 5 days and bad over 20 is a different instrument than one that only works held long.
 */
@RestController
@RequestMapping("/api")
public class BacktestController {

    /** Re-running the analyzer per bar is O(n^2); this bounds a single request's work. */
    private static final Set<String> ALLOWED_RANGES = Set.of("2y", "5y", "10y");

    private static final int[] DEFAULT_HORIZONS = {5, 10, 20};

    private final YahooDataSource source = new YahooDataSource();

    /**
     * GET /api/backtest?symbol=TATAELXSI.NS[&range=5y][&condition=buyNow|allGates]
     */
    @GetMapping("/backtest")
    public ResponseEntity<?> backtest(@RequestParam(value = "symbol", required = false) String rawSymbol,
                                      @RequestParam(value = "range", required = false) String rawRange,
                                      @RequestParam(value = "condition", required = false) String rawCondition) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing ?symbol="));
        }
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        String range = rawRange == null || rawRange.isBlank() ? "5y" : rawRange.trim().toLowerCase();
        if (!ALLOWED_RANGES.contains(range)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported ?range=" + rawRange
                    + " (allowed: " + ALLOWED_RANGES + ")"));
        }

        String condition = rawCondition == null || rawCondition.isBlank() ? "buyNow" : rawCondition.trim();
        var predicate = switch (condition) {
            case "buyNow" -> Backtester.buyNow();
            case "allGates" -> Backtester.allGatesPassed();
            default -> null;
        };
        if (predicate == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "unsupported ?condition=" + rawCondition
                            + " (allowed: buyNow, allGates)"));
        }

        try {
            List<Bar> bars = source.fetchDaily(symbol, range);
            if (bars.size() <= Backtester.WARMUP_BARS + DEFAULT_HORIZONS[DEFAULT_HORIZONS.length - 1]) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        symbol + ": only " + bars.size() + " bars in " + range
                                + "; need more than " + Backtester.WARMUP_BARS + " before any signal counts"));
            }

            String universe = ScanRunner.universeOf(symbol);
            List<Bar> benchmark = BenchmarkSource.barsFor(universe);
            String benchmarkName = BenchmarkSource.displayName(universe);

            List<Map<String, Object>> horizons = new ArrayList<>();
            for (int h : DEFAULT_HORIZONS) {
                horizons.add(Backtester.run(bars, benchmark, benchmarkName, predicate, condition, h).toRow());
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbol", symbol);
            payload.put("range", range);
            payload.put("condition", condition);
            payload.put("bars", bars.size());
            payload.put("warmupBars", Backtester.WARMUP_BARS);
            payload.put("horizons", horizons);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "backtest failed for " + symbol + ": " + e.getMessage()));
        }
    }
}
