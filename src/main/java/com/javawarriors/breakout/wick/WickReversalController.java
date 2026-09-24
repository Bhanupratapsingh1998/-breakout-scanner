package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.index500.SectorService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The wick-reversal API.
 *
 * <p>Filters run over the signals already in memory and never trigger a fetch, so narrowing by
 * status or sector is instant. Starting a scan is a separate, explicit POST - the same contract the
 * app's other scans use.
 */
@RestController
@RequestMapping("/api/wick-reversal")
public class WickReversalController {

    private final WickReversalService service;
    private final WickReversalConfig cfg;

    public WickReversalController(WickReversalService service, WickReversalConfig cfg) {
        this.service = service;
        this.cfg = cfg;
    }

    /** The timeframes on offer, and what each group size of them adds up to. */
    @GetMapping("/intervals")
    public Map<String, Object> intervals() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String iv : cfg.getIntervals()) {
            Map<String, String> merged = new LinkedHashMap<>();
            for (int count : cfg.getCandleCounts()) {
                merged.put(String.valueOf(count), WickReversalConfig.mergedLabel(iv, count));
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("interval", iv);
            row.put("merged", merged);
            row.put("range", cfg.rangeFor(iv));
            out.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("intervals", out);
        body.put("defaultInterval", cfg.getDefaultInterval());
        body.put("candleCounts", cfg.getCandleCounts());
        body.put("directions", cfg.getDirections());
        return body;
    }

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scan(@RequestParam(required = false) String interval) {
        if (!cfg.isKnownInterval(interval)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown interval '" + interval + "'",
                    "allowed", cfg.getIntervals()));
        }
        return service.triggerScan(interval)
                ? ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("status", "started"))
                : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", "already-running"));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return service.status();
    }

    /**
     * The signals from the last completed scan.
     *
     * <p>{@code status} defaults to excluding invalidated rows: a level price has already closed
     * through is history, and leading with it would bury the live ones. Pass {@code status=ALL} to
     * see everything the scan found.
     */
    @GetMapping("/results")
    public ResponseEntity<?> results(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String sector,
                                     @RequestParam(required = false) Integer candles,
                                     @RequestParam(required = false) String direction,
                                     @RequestParam(required = false) Double minScore,
                                     @RequestParam(required = false) Integer maxBarsAgo) {
        List<WickSignal> all = service.lastRun();
        if (all == null) return ResponseEntity.notFound().build();

        if (!cfg.isKnownCount(candles)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown candle count '" + candles + "'",
                    "allowed", cfg.getCandleCounts()));
        }
        if (!cfg.isKnownDirection(direction)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown direction '" + direction + "'",
                    "allowed", cfg.getDirections()));
        }

        List<WickSignal> filtered = new ArrayList<>();
        for (WickSignal s : all) {
            if (!SectorService.matches(sector, s.sector())) continue;
            if (candles != null && s.candles() != candles) continue;
            if (direction != null && !direction.isBlank() && !"ALL".equalsIgnoreCase(direction)
                    && !direction.equalsIgnoreCase(s.direction())) continue;
            if (!matchesStatus(status, s)) continue;
            if (minScore != null && !(s.score() >= minScore)) continue;
            if (maxBarsAgo != null && !(s.barsAgo() <= maxBarsAgo)) continue;
            filtered.add(s);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (WickSignal s : filtered) {
            Map<String, Object> row = s.toRow();
            row.put("rank", rank++);
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", service.generatedAt());
        body.put("interval", service.lastInterval());
        body.put("candleCounts", cfg.getCandleCounts());
        body.put("directions", cfg.getDirections());
        body.put("total", all.size());
        body.put("matched", filtered.size());
        body.putAll(counts(all));
        body.put("sectors", sectorsPresent(all));
        body.put("signals", rows);
        return ResponseEntity.ok(body);
    }

    private static boolean matchesStatus(String status, WickSignal s) {
        if (status == null || status.isBlank()) return !WickSignal.INVALIDATED.equals(s.status());
        if ("ALL".equalsIgnoreCase(status)) return true;
        return status.equalsIgnoreCase(s.status());
    }

    private static Map<String, Object> counts(List<WickSignal> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pendingCount", rows.stream().filter(r -> WickSignal.PENDING.equals(r.status())).count());
        out.put("confirmedCount", rows.stream().filter(r -> WickSignal.CONFIRMED.equals(r.status())).count());
        out.put("invalidatedCount", rows.stream().filter(r -> WickSignal.INVALIDATED.equals(r.status())).count());
        out.put("freshCount", rows.stream().filter(r -> r.barsAgo() == 0).count());
        // Per group size, so the filter can show what selecting it would give before it is clicked.
        Map<String, Long> byCandles = new LinkedHashMap<>();
        for (WickSignal r : rows) {
            byCandles.merge(String.valueOf(r.candles()), 1L, Long::sum);
        }
        out.put("byCandles", byCandles);
        Map<String, Long> byDirection = new LinkedHashMap<>();
        for (WickSignal r : rows) byDirection.merge(r.direction(), 1L, Long::sum);
        out.put("byDirection", byDirection);
        out.put("bullishCount", byDirection.getOrDefault(WickSignal.BULLISH, 0L));
        out.put("bearishCount", byDirection.getOrDefault(WickSignal.BEARISH, 0L));
        return out;
    }

    /** Only the sectors that actually produced a signal, so the filter cannot select an empty set. */
    private static List<String> sectorsPresent(List<WickSignal> rows) {
        return rows.stream().map(WickSignal::sector).distinct().sorted().toList();
    }
}
