package com.javawarriors.breakout.index500;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Who a symbol is, independent of anything the scanners compute about it.
 *
 * <p>Kept separate from the analysis so sector membership can change - NSE rebalances the index and
 * reclassifies industries - without touching a line of scanner logic. Nothing here is derived from
 * price.
 *
 * @param sector     NSE's own industry classification, e.g. "Information Technology"
 * @param industry   a finer classification when one is known; NSE's Nifty 500 CSV carries only the
 *                   one column, so today this mirrors {@code sector} rather than inventing a split
 * @param indexName  which index membership put this symbol in the universe
 * @param lotSize    the F&O contract's lot size, or 0 when the symbol has no derivatives or when
 *                   the contract file could not be read. Metadata rather than analysis: NSE sets
 *                   it, nothing here computes it, and it changes on NSE's schedule and not on
 *                   price.
 */
public record StockMetadata(String symbol, String companyName, String sector, String industry,
                            String exchange, String indexName, int lotSize) {

    public static final String UNKNOWN_SECTOR = "Unclassified";

    /** For the equity universes, where no contract exists. */
    public StockMetadata(String symbol, String companyName, String sector, String industry,
                         String exchange, String indexName) {
        this(symbol, companyName, sector, industry, exchange, indexName, 0);
    }

    /** True when this symbol trades in the derivatives segment. */
    public boolean hasDerivatives() {
        return lotSize > 0;
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("companyName", companyName);
        row.put("sector", sector);
        row.put("industry", industry);
        row.put("exchange", exchange);
        row.put("indexName", indexName);
        // Absent rather than zero: "no lot size known" and "a lot size of nothing" are different
        // claims, and the table renders the first as a dash.
        row.put("lotSize", lotSize > 0 ? lotSize : null);
        return row;
    }
}
