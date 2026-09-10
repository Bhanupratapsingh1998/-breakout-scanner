package com.javawarriors.breakout.scan;

import com.javawarriors.breakout.breakout.BreakoutAnalyzer;
import com.javawarriors.breakout.breakout.BreakoutResult;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.tradesetup.TradeSetupAnalyzer;
import com.javawarriors.breakout.tradesetup.TradeSetupResult;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Runs the full Nifty 50 + Next 50 + Nifty 500 breakout scan and builds the API result payload. */
public class ScanRunner {

    public record ScanOutcome(
            List<BreakoutResult> results, List<TradeSetupResult> reversals,
            int failed, int universeSize, Map<String, String> nifty500Names) {}

    /** Symbol -> tier: NIFTY_50, NEXT_50, or NIFTY_500. */
    public static String universeOf(String symbol) {
        if (NiftyUniverse.NIFTY_50.contains(symbol)) return "NIFTY_50";
        if (NiftyUniverse.NIFTY_NEXT_50.contains(symbol)) return "NEXT_50";
        return "NIFTY_500";
    }

    /** Curated top-100 + live Nifty 500 constituents (deduped), in that order. */
    public static List<String> buildWatchlist(Map<String, String> nifty500Names) {
        List<String> watchlist = new ArrayList<>(NiftyUniverse.ALL);
        Set<String> seen = new HashSet<>(NiftyUniverse.ALL);
        for (String symbol : nifty500Names.keySet()) {
            if (seen.add(symbol)) watchlist.add(symbol);
        }
        return watchlist;
    }

    /** Fetches + analyzes the full watchlist. onProgress gets "SYMBOL (i/n)" as it goes. */
    public static ScanOutcome runFullScan(Consumer<String> onProgress) {
        Map<String, String> nifty500Names;
        try {
            nifty500Names = NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            // degrade to the curated top-100 + whatever was cached from a previous successful fetch
            nifty500Names = NseIndexSource.cached();
        }

        BenchmarkSource.refreshAll(); // best-effort per index; degrades to whatever was already cached

        List<String> watchlist = buildWatchlist(nifty500Names);
        BreakoutAnalyzer analyzer = new BreakoutAnalyzer();
        TradeSetupAnalyzer tradeSetupAnalyzer = new TradeSetupAnalyzer();
        YahooDataSource source = new YahooDataSource();
        List<BreakoutResult> results = new ArrayList<>();
        List<TradeSetupResult> reversals = new ArrayList<>();
        int failed = 0;

        for (int i = 0; i < watchlist.size(); i++) {
            String symbol = watchlist.get(i);
            if (onProgress != null) onProgress.accept(symbol + " (" + (i + 1) + "/" + watchlist.size() + ")");
            try {
                List<Bar> bars = source.fetchDaily(symbol, "18mo");
                if (bars.size() < 200) {
                    failed++;
                    continue;
                }
                String universe = universeOf(symbol);
                results.add(analyzer.analyze(symbol, bars, BenchmarkSource.barsFor(universe),
                        BenchmarkSource.displayName(universe)));
                // The same fetched bars feed the reversal/candlestick layer too — no second
                // round of requests.
                TradeSetupResult setup = tradeSetupAnalyzer.scoreReversalSetup(symbol, bars);
                if (!"REJECTED".equals(setup.classification)) reversals.add(setup);
            } catch (Exception e) {
                failed++;
            }
            try {
                Thread.sleep(150); // be polite to Yahoo's endpoint across hundreds of requests
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        results.sort((a, b) -> {
            int t = b.tierRank() - a.tierRank();
            return t != 0 ? t : b.score() - a.score();
        });
        reversals.sort((a, b) -> b.candlestickScore - a.candlestickScore);
        return new ScanOutcome(results, reversals, failed, watchlist.size(), nifty500Names);
    }

    /** Builds the JSON-shaped result payload served by GET /api/results. */
    public static Map<String, Object> buildPayload(List<BreakoutResult> results, List<TradeSetupResult> reversals,
                                                     int universeSize, Map<String, String> nifty500Names) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (BreakoutResult r : results) {
            String name = nifty500Names.getOrDefault(r.symbol, NiftyUniverse.NAMES.getOrDefault(r.symbol, r.symbol));
            out.add(r.toRow(name, universeOf(r.symbol)));
        }

        List<Map<String, Object>> reversalOut = new ArrayList<>();
        for (TradeSetupResult r : reversals) {
            String name = nifty500Names.getOrDefault(r.symbol, NiftyUniverse.NAMES.getOrDefault(r.symbol, r.symbol));
            reversalOut.add(r.toRow(name, universeOf(r.symbol)));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("universe", universeSize);
        payload.put("results", out);
        payload.put("reversals", reversalOut);
        return payload;
    }
}
