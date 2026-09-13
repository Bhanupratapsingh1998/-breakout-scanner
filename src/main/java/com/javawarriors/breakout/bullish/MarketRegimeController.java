package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.model.Bar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The market regime on its own, with no universe scan behind it.
 *
 * <p>Exists so the dashboard has something true to show the moment the app opens. The regime is
 * derived from two index series that {@code BreakoutScannerApplication} already warms on startup,
 * so this costs nothing beyond a cache read — unlike every other panel in the app, which needs a
 * few hundred symbols fetched before it can say anything.
 *
 * <p>That distinction is the whole point: "what is the market doing" is answerable immediately,
 * and a dashboard that made the user run a five-minute scan before telling them that would be
 * withholding the one thing it already knew.
 */
@RestController
@RequestMapping("/api/market-regime")
public class MarketRegimeController {

    private final BullishConfig cfg;

    public MarketRegimeController(BullishConfig cfg) {
        this.cfg = cfg;
    }

    @GetMapping
    public ResponseEntity<?> regime() {
        try {
            List<Bar> nifty50 = BenchmarkSource.barsFor("NIFTY_50");
            List<Bar> nifty500 = BenchmarkSource.barsFor("NIFTY_500");

            // Startup warms these, but a cold container hit before the warmup thread finished — or
            // an index that failed to fetch then — should retry rather than report "no data".
            if (nifty50 == null && nifty500 == null) {
                BenchmarkSource.refreshAll();
                nifty50 = BenchmarkSource.barsFor("NIFTY_50");
                nifty500 = BenchmarkSource.barsFor("NIFTY_500");
            }

            return ResponseEntity.ok(MarketRegimeAnalyzer.analyze(nifty50, nifty500, cfg).toRow());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "could not read the index data: " + e.getMessage()));
        }
    }
}
