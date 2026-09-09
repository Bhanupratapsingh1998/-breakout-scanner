package com.javawarriors.breakout.marketdata;

import com.javawarriors.breakout.model.Bar;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Caches one benchmark index per universe tier, so relative-strength comparisons use the
 * right index for the stock being scored — a Nifty 500 midcap shouldn't be judged against
 * the Nifty 50 large-cap index.
 */
public class BenchmarkSource {

    /** universe tier -> {display name, Yahoo symbol}. Nifty Next 50's raw index isn't on
     *  Yahoo, so JUNIORBEES.NS (the long-running Nippon India ETF tracking it) stands in. */
    private static final Map<String, String[]> BENCHMARKS = Map.of(
            "NIFTY_50", new String[]{"Nifty 50", "^NSEI"},
            "NEXT_50", new String[]{"Nifty Next 50", "JUNIORBEES.NS"},
            "NIFTY_500", new String[]{"Nifty 500", "^CRSLDX"}
    );

    private static final YahooDataSource SOURCE = new YahooDataSource();
    private static final Map<String, List<Bar>> CACHE = new ConcurrentHashMap<>();

    /** Re-fetches all three benchmarks. Best-effort per index — one failing doesn't block the rest. */
    public static void refreshAll() {
        for (Map.Entry<String, String[]> e : BENCHMARKS.entrySet()) {
            try {
                CACHE.put(e.getKey(), SOURCE.fetchDaily(e.getValue()[1], "18mo"));
            } catch (Exception ignored) {
                // keep whatever was cached before, if anything
            }
        }
    }

    public static String displayName(String universe) {
        String[] meta = BENCHMARKS.get(universe);
        return meta != null ? meta[0] : "benchmark";
    }

    /** Cached bars for the given universe tier ("NIFTY_50", "NEXT_50", "NIFTY_500"), or null. */
    public static List<Bar> barsFor(String universe) {
        return CACHE.get(universe);
    }
}
