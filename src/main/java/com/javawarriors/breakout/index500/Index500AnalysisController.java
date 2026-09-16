package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.index500.pattern.PatternRegistry;

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
 * The Index 500 Analysis API.
 *
 * <p>Filtering runs over the last completed scan held in memory - it never triggers a fetch - so
 * every combination of sector, pattern and threshold answers immediately. Starting a scan is a
 * separate, explicit POST, following the same contract as the app's other scans.
 */
@RestController
@RequestMapping("/api/index500")
public class Index500AnalysisController {

    private final Index500AnalysisService service;
    private final SectorService sectors;
    private final SectorAnalysisService sectorAnalysis;
    private final PerformanceRankingService ranking;
    private final PatternRegistry patterns;
    private final Index500Config cfg;

    public Index500AnalysisController(Index500AnalysisService service, SectorService sectors,
                                      SectorAnalysisService sectorAnalysis,
                                      PerformanceRankingService ranking, PatternRegistry patterns,
                                      Index500Config cfg) {
        this.service = service;
        this.sectors = sectors;
        this.sectorAnalysis = sectorAnalysis;
        this.ranking = ranking;
        this.patterns = patterns;
        this.cfg = cfg;
    }

    /** Every sector in the universe, with how many symbols carry it. */
    @GetMapping("/sectors")
    public Map<String, Object> sectors() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sectors", sectors.sectors());
        body.put("counts", sectors.sectorCounts());
        return body;
    }

    /** The pattern filter's vocabulary: type -> display name. */
    @GetMapping("/patterns")
    public Map<String, Object> patterns() {
        return Map.of("patterns", patterns.catalogue());
    }

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

    /** Sector-level performance, weakest six months first. */
    @GetMapping("/sector-summary")
    public ResponseEntity<Map<String, Object>> sectorSummary() {
        List<Index500Analysis> rows = service.lastRun();
        if (rows == null) return ResponseEntity.notFound().build();

        List<Map<String, Object>> out = new ArrayList<>();
        for (SectorAnalysisService.SectorSummary s : sectorAnalysis.summarise(rows)) out.add(s.toRow());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", service.generatedAt());
        body.put("sectors", out);
        return ResponseEntity.ok(body);
    }

    /**
     * The main query. Every parameter is optional; omitting all of them returns the whole universe
     * ranked by opportunity score.
     *
     * <p>{@code minDrop}/{@code maxDrop} are expressed as a <em>fall</em>, so {@code minDrop=15}
     * means "down at least 15%" - which is how the question is asked, rather than making the caller
     * reason about a negative return.
     */
    @GetMapping("/analysis")
    public ResponseEntity<?> analysis(
            @RequestParam(required = false) String sector,
            @RequestParam(required = false) String pattern,
            @RequestParam(required = false) Double minDrop,
            @RequestParam(required = false) Double maxDrop,
            @RequestParam(required = false) Double minRsi,
            @RequestParam(required = false) Double maxRsi,
            @RequestParam(required = false) Double minVolumeRatio,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDirection,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean includeUnavailable) {

        List<Index500Analysis> all = service.lastRun();
        if (all == null) return ResponseEntity.notFound().build();

        if (!patterns.isKnownType(pattern)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown pattern '" + pattern + "'",
                    "allowed", patterns.catalogue().keySet()));
        }
        // An unknown sector is a typo, not an empty result — saying so beats returning zero rows.
        if (sector != null && !sector.isBlank() && !"ALL".equalsIgnoreCase(sector)
                && sectors.sectors().stream().noneMatch(s -> s.equalsIgnoreCase(sector.trim()))) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "unknown sector '" + sector + "'",
                    "allowed", sectors.sectors()));
        }

        List<Index500Analysis> filtered = new ArrayList<>();
        for (Index500Analysis r : all) {
            if (!SectorService.matches(sector, r.sector())) continue;
            if (!r.analysed()) {
                // Unavailable rows carry no metrics, so every numeric filter would drop them. They
                // are included only when explicitly asked for, and only on the sector filter.
                if (includeUnavailable && pattern == null && minDrop == null && maxDrop == null
                        && minRsi == null && maxRsi == null && minVolumeRatio == null) {
                    filtered.add(r);
                }
                continue;
            }
            if (!r.hasPattern(pattern)) continue;
            if (minDrop != null && !(r.drop6mPct() >= minDrop)) continue;
            if (maxDrop != null && !(r.drop6mPct() <= maxDrop)) continue;
            if (minRsi != null && !(r.rsi() >= minRsi)) continue;
            if (maxRsi != null && !(r.rsi() <= maxRsi)) continue;
            if (minVolumeRatio != null && !(r.volumeRatio() >= minVolumeRatio)) continue;
            if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status)
                    && !status.equalsIgnoreCase(r.status())) continue;
            filtered.add(r);
        }

        List<Index500Analysis> ordered = ranking.rank(filtered, sortBy, sortDirection);
        // The decline rank is always computed on the same filtered set, so "6M Rank" means the same
        // thing whatever the table is currently sorted by.
        Map<String, Integer> declineRanks = PerformanceRankingService.rankMap(
                ranking.biggestDecliners(filtered));

        int cap = limit != null && limit > 0 ? Math.min(limit, cfg.getMaxResults()) : cfg.getMaxResults();
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Index500Analysis r : ordered) {
            if (rows.size() >= cap) break;
            Map<String, Object> row = r.toRow();
            row.put("rank", rank++);
            row.put("declineRank", declineRanks.get(r.symbol()));
            rows.add(row);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", service.generatedAt());
        body.put("universeSize", all.size());
        body.put("matched", filtered.size());
        body.put("returned", rows.size());
        body.putAll(summaryCounts(filtered));
        body.put("filters", filters(sector, pattern, minDrop, maxDrop, minRsi, maxRsi,
                minVolumeRatio, status, sortBy, sortDirection));
        body.put("stocks", rows);
        return ResponseEntity.ok(body);
    }

    /** Ranking A, as its own endpoint. */
    @GetMapping("/top-decliners")
    public ResponseEntity<?> topDecliners(@RequestParam(required = false) String sector,
                                          @RequestParam(required = false) Integer limit) {
        return rankedSubset(sector, limit, true);
    }

    /** Ranking B, as its own endpoint. */
    @GetMapping("/recovery-candidates")
    public ResponseEntity<?> recoveryCandidates(@RequestParam(required = false) String sector,
                                                @RequestParam(required = false) Integer limit) {
        return rankedSubset(sector, limit, false);
    }

    private ResponseEntity<?> rankedSubset(String sector, Integer limit, boolean decliners) {
        List<Index500Analysis> all = service.lastRun();
        if (all == null) return ResponseEntity.notFound().build();

        List<Index500Analysis> scoped = all.stream()
                .filter(Index500Analysis::analysed)
                .filter(r -> SectorService.matches(sector, r.sector()))
                .toList();
        List<Index500Analysis> ordered = decliners
                ? ranking.biggestDecliners(scoped) : ranking.strongestRecovery(scoped);

        int cap = limit != null && limit > 0 ? Math.min(limit, cfg.getMaxResults()) : 25;
        List<Map<String, Object>> rows = new ArrayList<>();
        int rank = 1;
        for (Index500Analysis r : ordered) {
            if (rows.size() >= cap) break;
            Map<String, Object> row = r.toRow();
            row.put("rank", rank++);
            rows.add(row);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", service.generatedAt());
        body.put("sector", sector == null || sector.isBlank() ? "ALL" : sector);
        body.put("stocks", rows);
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> summaryCounts(List<Index500Analysis> rows) {
        long decliners = rows.stream().filter(r -> r.analysed() && r.return6mPct() < 0).count();
        long reversals = rows.stream().filter(r ->
                Index500Analysis.STRONG_REVERSAL.equals(r.status())
                        || Index500Analysis.REVERSAL_WATCH.equals(r.status())).count();
        long withPattern = rows.stream().filter(r -> r.bestPattern() != null).count();
        long breakouts = rows.stream().filter(r ->
                Index500Analysis.BREAKOUT_CONFIRMED.equals(r.status())
                        || Index500Analysis.BREAKOUT_CANDIDATE.equals(r.status())).count();
        long unavailable = rows.stream().filter(r -> !r.analysed()).count();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("declinerCount", decliners);
        out.put("reversalCount", reversals);
        out.put("patternCount", withPattern);
        out.put("breakoutCount", breakouts);
        out.put("unavailableCount", unavailable);
        return out;
    }

    private static Map<String, Object> filters(String sector, String pattern, Double minDrop,
                                               Double maxDrop, Double minRsi, Double maxRsi,
                                               Double minVolumeRatio, String status, String sortBy,
                                               String sortDirection) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("sector", sector == null || sector.isBlank() ? "ALL" : sector);
        f.put("pattern", pattern == null || pattern.isBlank() ? "ALL" : pattern);
        f.put("minDrop", minDrop);
        f.put("maxDrop", maxDrop);
        f.put("minRsi", minRsi);
        f.put("maxRsi", maxRsi);
        f.put("minVolumeRatio", minVolumeRatio);
        f.put("status", status == null || status.isBlank() ? "ALL" : status);
        f.put("sortBy", sortBy == null || sortBy.isBlank() ? "score" : sortBy);
        f.put("sortDirection", sortDirection == null || sortDirection.isBlank() ? "desc" : sortDirection);
        return f;
    }
}
