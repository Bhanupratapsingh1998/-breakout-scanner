package com.javawarriors.breakout.scan;

import com.javawarriors.breakout.marketdata.BarCache;
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

/**
 * Walks the Nifty 500 looking for candlestick-confirmed reversal setups.
 *
 * <p>This used to run the Breakout Scanner's A-J checklist as well, with the reversal pass riding
 * along on the same fetched bars. The breakout feature has been removed and the reversal pass is
 * what remains — it never depended on the checklist, only on the bars.
 *
 * <p>The universe helpers below are still shared: the Bullish Stocks engine builds its own
 * watchlist from {@link #buildWatchlist} and tags rows with {@link #universeOf}, so both scans
 * cover exactly the same symbols and tier them the same way.
 */
public class ScanRunner {

    public record ScanOutcome(List<TradeSetupResult> reversals, int failed, int universeSize,
                              Map<String, String> nifty500Names) {}

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
        TradeSetupAnalyzer tradeSetupAnalyzer = new TradeSetupAnalyzer();
        YahooDataSource source = new YahooDataSource();
        List<TradeSetupResult> reversals = new ArrayList<>();
        int failed = 0;

        for (int i = 0; i < watchlist.size(); i++) {
            String symbol = watchlist.get(i);
            if (onProgress != null) onProgress.accept(symbol + " (" + (i + 1) + "/" + watchlist.size() + ")");
            // Nothing was requested from Yahoo on a cache hit, so there is nothing to be polite
            // about — the throttle below applies only to symbols that actually hit the network.
            boolean servedFromCache = BarCache.peek(symbol, "18mo") != null;
            try {
                // Through the shared cache so a bullish scan run in the same session reuses
                // these bars instead of downloading the whole universe a second time.
                List<Bar> bars = BarCache.daily(source, symbol, "18mo");
                if (bars.size() < 200) {
                    failed++;
                    continue;
                }
                TradeSetupResult setup = tradeSetupAnalyzer.scoreReversalSetup(symbol, bars);
                if (!"REJECTED".equals(setup.classification)) reversals.add(setup);
            } catch (Exception e) {
                failed++;
            }
            if (!servedFromCache) {
                try {
                    Thread.sleep(150); // be polite to Yahoo's endpoint across hundreds of requests
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        reversals.sort((a, b) -> b.candlestickScore - a.candlestickScore);
        return new ScanOutcome(reversals, failed, watchlist.size(), nifty500Names);
    }

    /** Builds the JSON-shaped result payload served by GET /api/results. */
    public static Map<String, Object> buildPayload(List<TradeSetupResult> reversals,
                                                     int universeSize, Map<String, String> nifty500Names) {
        List<Map<String, Object>> reversalOut = new ArrayList<>();
        for (TradeSetupResult r : reversals) {
            String name = nifty500Names.getOrDefault(r.symbol, NiftyUniverse.NAMES.getOrDefault(r.symbol, r.symbol));
            reversalOut.add(r.toRow(name, universeOf(r.symbol)));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("generatedAt", Instant.now().toString());
        payload.put("universe", universeSize);
        payload.put("reversals", reversalOut);
        return payload;
    }
}
