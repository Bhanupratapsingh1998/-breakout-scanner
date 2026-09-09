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

/**
 * Plain OHLCV data for charting — deliberately does no analysis of its own. The row a chart is
 * opened from already carries "Prev resistance" / "Breakout Confirm Level" / "Breakout Bar Time"
 * from whichever scan produced it (BreakoutAnalyzer); this endpoint just supplies the candles
 * for those levels to be drawn against.
 */
@RestController
@RequestMapping("/api")
public class ChartController {

    private static final int MAX_BARS = 130;

    private final YahooDataSource source = new YahooDataSource();

    /** GET /api/chart?symbol=TATAELXSI.NS — trailing ~130 daily candles. */
    @GetMapping("/chart")
    public ResponseEntity<?> chart(@RequestParam(value = "symbol", required = false) String rawSymbol) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing ?symbol="));
        }
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        try {
            List<Bar> bars = source.fetchDaily(symbol, "18mo");
            int from = Math.max(0, bars.size() - MAX_BARS);
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
            payload.put("bars", out);
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "fetch failed for " + symbol + ": " + e.getMessage()));
        }
    }
}
