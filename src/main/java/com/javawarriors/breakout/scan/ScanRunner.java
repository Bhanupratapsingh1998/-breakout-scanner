package com.javawarriors.breakout.scan;

import com.javawarriors.breakout.marketdata.NiftyUniverse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Defines the Nifty 500 universe every scan walks, and how its symbols are tiered.
 *
 * <p>All that is left of this class. It once ran the Breakout Scanner's A-J checklist, then the
 * candlestick reversal pass; both features have been removed. The universe helpers survive because
 * they are what the remaining features share: the Index 500 analysis and the watchlist scorer both
 * build their symbol lists from {@link #buildWatchlist} and tag rows with {@link #universeOf}, so
 * every scan covers exactly the same symbols and tiers them the same way.
 */
public class ScanRunner {

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

}
