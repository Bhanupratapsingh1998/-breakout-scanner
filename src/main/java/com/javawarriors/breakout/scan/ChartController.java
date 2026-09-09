package com.javawarriors.breakout.scan;

import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;

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
 * Plain OHLCV data for charting — deliberately does no analysis of its own. The row a chart is
 * opened from already carries "Prev resistance" / "Breakout Confirm Level" / "Breakout Bar Time"
 * from whichever scan produced it (BreakoutAnalyzer); this endpoint just supplies the candles
 * for those levels to be drawn against.
 */
@RestController
@RequestMapping("/api")
public class ChartController {

    /** Bar cap for the inline mini-chart (no ?range=), which draws a fixed-width SVG. */
    private static final int MAX_BARS = 130;

    /** Ceiling for an explicit ?range= request, so ?range=max on an old listing stays a sane payload. */
    private static final int MAX_RANGE_BARS = 5000;

    /**
     * Ranges the fullscreen chart's timeframe tabs may ask for. Whitelisted rather than passed
     * through, so a bad value can't be reflected into the upstream Yahoo URL.
     *
     * <p>"max" is deliberately absent: Yahoo silently downgrades it to monthly candles
     * (meta.dataGranularity comes back "1mo") no matter what interval is asked for, and monthly
     * bars under daily-derived resistance levels would misread badly. "10y" is the longest range
     * Yahoo still serves at daily granularity.
     */
    private static final Set<String> ALLOWED_RANGES = Set.of("1mo", "3mo", "6mo", "1y", "2y", "5y", "10y");

    private final YahooDataSource source = new YahooDataSource();

    /**
     * GET /api/chart?symbol=TATAELXSI.NS[&range=6mo] — daily candles.
     * Without {@code range} it returns the trailing ~130 bars the inline mini-chart expects;
     * with one it returns the whole requested window for the fullscreen chart.
     */
    @GetMapping("/chart")
    public ResponseEntity<?> chart(@RequestParam(value = "symbol", required = false) String rawSymbol,
                                   @RequestParam(value = "range", required = false) String rawRange) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing ?symbol="));
        }
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        boolean ranged = rawRange != null && !rawRange.isBlank();
        String range = ranged ? rawRange.trim().toLowerCase() : "18mo";
        if (ranged && !ALLOWED_RANGES.contains(range)) {
            return ResponseEntity.badRequest().body(Map.of("error", "unsupported ?range=" + rawRange));
        }

        try {
            List<Bar> bars = source.fetchDaily(symbol, range);
            int cap = ranged ? MAX_RANGE_BARS : MAX_BARS;
            int from = Math.max(0, bars.size() - cap);
            List<Map<String, Object>> out = new ArrayList<>();
            for (Bar b : bars.subList(from, bars.size())) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("time", b.time());
                row.put("open", b.open());
                row.put("high", b.high());
                row.put("low", b.low());
                row.put("close", b.close());
                row.put("volume", b.volume());
                out.add(row);
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbol", symbol);
            payload.put("range", range);
            payload.put("bars", out);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "fetch failed for " + symbol + ": " + e.getMessage()));
        }
    }
}
