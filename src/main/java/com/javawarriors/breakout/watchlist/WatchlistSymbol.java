package com.javawarriors.breakout.watchlist;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A symbol the user asked the scanner to follow that is not in the Nifty universe.
 *
 * <p>Previously this list lived in the browser's localStorage, which meant it was per-browser,
 * per-device, invisible to the server, and silently lost on a cache clear. Holding it in the same
 * database as the trade journal makes the watchlist follow the user rather than the machine.
 */
@Entity
public class WatchlistSymbol {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Yahoo-style symbol, always upper case and suffixed (RELIANCE.NS, TATAELXSI.BO). */
    @Column(nullable = false, unique = true, length = 32)
    private String symbol;

    @Column(nullable = false)
    private Instant addedAt;

    protected WatchlistSymbol() {
        // JPA
    }

    public WatchlistSymbol(String symbol) {
        this.symbol = symbol;
        this.addedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSymbol() { return symbol; }
    public Instant getAddedAt() { return addedAt; }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("addedAt", addedAt.toString());
        return row;
    }
}
