package com.javawarriors.breakout.watchlist;

import jakarta.transaction.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * The user's own watchlist: symbols outside the scanned Nifty universe that they want followed.
 *
 * <p>Stores only the symbol. Analysis is deliberately not cached here — a stored verdict would go
 * stale the moment the next session closes, and GET /api/analyze already computes it live from
 * fresh bars.
 */
@RestController
@RequestMapping("/api/watchlist")
public class WatchlistController {

    public record AddSymbolRequest(String symbol) {}

    private final WatchlistRepository repo;

    public WatchlistController(WatchlistRepository repo) {
        this.repo = repo;
    }

    /**
     * Applies the same normalisation as the analyze endpoint, so a symbol saved as "tataelxsi"
     * and one saved as "TATAELXSI.NS" cannot become two rows for the same stock.
     */
    private static String normalise(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return null;
        if (!s.contains(".")) s += ".NS";
        return s;
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return repo.findAllByOrderByAddedAtAsc().stream().map(WatchlistSymbol::toRow).toList();
    }

    @PostMapping
    public ResponseEntity<?> add(@RequestBody AddSymbolRequest req) {
        String symbol = normalise(req == null ? null : req.symbol());
        if (symbol == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol is required"));
        }
        if (symbol.length() > 32) {
            return ResponseEntity.badRequest().body(Map.of("error", "symbol is too long"));
        }
        // Adding something already followed is a no-op, not an error: the UI calls this on every
        // successful lookup, and a duplicate should not surface as a failure to the user.
        WatchlistSymbol saved = repo.findBySymbol(symbol)
                .orElseGet(() -> repo.save(new WatchlistSymbol(symbol)));
        return ResponseEntity.status(HttpStatus.CREATED).body(saved.toRow());
    }

    @DeleteMapping("/{symbol}")
    @Transactional
    public ResponseEntity<Void> remove(@PathVariable("symbol") String symbol) {
        String normalised = normalise(symbol);
        if (normalised != null) repo.findBySymbol(normalised).ifPresent(repo::delete);
        return ResponseEntity.noContent().build();
    }
}
