package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.marketdata.FnoUniverseSource;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.scan.ScanRunner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The one place that answers "what sector is this symbol in".
 *
 * <p>Centralised deliberately. Sector membership is the axis this whole feature pivots on, and if
 * the mapping were read inline wherever it was needed, a change of data source - or NSE renaming an
 * industry - would mean hunting every call site. Every other class here asks this service.
 *
 * <p>The data is NSE's own Industry column from the Nifty 500 constituent CSV, which
 * {@code NseIndexSource} already parses. No second fetch, and no hand-maintained sector list to
 * drift out of date: when NSE reclassifies a stock, the next refresh picks it up.
 *
 * <p>A symbol with no classification - a curated Nifty 100 name that is not in the 500 CSV, or a
 * CSV that failed to load - is reported as {@link StockMetadata#UNKNOWN_SECTOR} rather than dropped,
 * so it stays visible under "All sectors" instead of silently vanishing from the universe.
 */
@Service
public class SectorService {

    private static final Logger log = LoggerFactory.getLogger(SectorService.class);

    /** Every symbol in the Nifty 500 universe with its metadata, in universe order. */
    public List<StockMetadata> universe() {
        return universe(Universe.NIFTY_500);
    }

    /**
     * Every symbol in {@code which} with its metadata, in that universe's own order.
     *
     * <p>Sector labels come from the same NSE Nifty 500 CSV whatever the universe, because that is
     * the only classification NSE publishes. Most F&O names are in the 500 and so are classified;
     * the handful that are not read as {@link StockMetadata#UNKNOWN_SECTOR} rather than being
     * dropped, so they stay visible under "All sectors" and in the table.
     */
    public List<StockMetadata> universe(Universe which) {
        Map<String, String> names = ensureMembership();
        List<StockMetadata> out = new ArrayList<>();
        for (String symbol : symbolsOf(which, names)) {
            out.add(metadataFor(symbol, names, which));
        }
        return out;
    }

    private static List<String> symbolsOf(Universe which, Map<String, String> names) {
        return which == Universe.FNO
                ? FnoUniverseSource.symbols()
                : ScanRunner.buildWatchlist(names);
    }

    public StockMetadata metadataFor(String symbol) {
        return metadataFor(symbol, ensureMembership(), Universe.NIFTY_500);
    }

    /**
     * The membership map, fetched on demand when nothing is cached yet.
     *
     * <p>Startup warms this in the background, but the sector dropdown is the first thing this tab
     * asks for and a request landing in those first seconds would otherwise get a list containing
     * only "Unclassified" - a filter that cannot filter. A failed fetch degrades to whatever is
     * cached rather than throwing: an unreachable NSE should cost sector labels, not the feature.
     */
    private Map<String, String> ensureMembership() {
        Map<String, String> names = NseIndexSource.cached();
        if (!names.isEmpty()) return names;
        try {
            return NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            log.warn("Nifty 500 membership unavailable, sectors will read as unclassified: {}",
                    e.getMessage());
            return NseIndexSource.cached();
        }
    }

    private StockMetadata metadataFor(String symbol, Map<String, String> names, Universe which) {
        String sector = normalise(NseIndexSource.sectorOf(symbol));
        String fallbackName = symbol.replaceAll("\\.(NS|BO)$", "");
        // Preference order is most-curated first. The contract file's UNDERLYING column is the
        // last resort for an F&O name outside the 500, where it is the only name available.
        String company = names.get(symbol);
        if (company == null) company = NiftyUniverse.NAMES.get(symbol);
        if (company == null && which == Universe.FNO) company = FnoUniverseSource.nameOf(symbol);
        if (company == null) company = fallbackName;
        String exchange = symbol.endsWith(".BO") ? "BSE" : "NSE";
        // ScanRunner already tiers the universe; reusing it keeps this feature's idea of index
        // membership identical to every other scan's.
        String index = switch (ScanRunner.universeOf(symbol)) {
            case "NIFTY_50" -> "Nifty 50";
            case "NEXT_50" -> "Nifty Next 50";
            // An F&O name outside the 500 is not a Nifty 500 constituent, and labelling it one
            // would be wrong on the one universe where that mislabelling is most likely.
            default -> which == Universe.FNO && !names.containsKey(symbol) ? "F&O" : "Nifty 500";
        };
        int lot = which == Universe.FNO ? FnoUniverseSource.lotSizeOf(symbol) : 0;
        return new StockMetadata(symbol, company, sector, sector, exchange, index, lot);
    }

    /** Every distinct sector present in the universe, alphabetically, for the filter dropdown. */
    public List<String> sectors() {
        return sectors(Universe.NIFTY_500);
    }

    /** Every distinct sector present in {@code which}, alphabetically. */
    public List<String> sectors(Universe which) {
        TreeSet<String> out = new TreeSet<>();
        for (StockMetadata m : universe(which)) out.add(m.sector());
        return new ArrayList<>(out);
    }

    /** Sector -> how many symbols carry it, for showing counts beside the filter. */
    public Map<String, Integer> sectorCounts() {
        return sectorCounts(Universe.NIFTY_500);
    }

    /** Sector -> how many symbols in {@code which} carry it. */
    public Map<String, Integer> sectorCounts(Universe which) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (StockMetadata m : universe(which)) counts.merge(m.sector(), 1, Integer::sum);
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()))
                .collect(LinkedHashMap::new, (acc, e) -> acc.put(e.getKey(), e.getValue()), LinkedHashMap::putAll);
    }

    /** True when {@code sector} is the "no filter" sentinel or matches, case- and space-insensitively. */
    public static boolean matches(String filter, String sector) {
        if (filter == null || filter.isBlank() || "ALL".equalsIgnoreCase(filter)) return true;
        return normalise(sector).equalsIgnoreCase(normalise(filter));
    }

    private static String normalise(String sector) {
        if (sector == null || sector.isBlank()) return StockMetadata.UNKNOWN_SECTOR;
        return sector.trim().replaceAll("\\s+", " ");
    }
}
