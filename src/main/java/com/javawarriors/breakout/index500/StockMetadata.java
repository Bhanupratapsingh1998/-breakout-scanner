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
 */
public record StockMetadata(String symbol, String companyName, String sector, String industry,
                            String exchange, String indexName) {

    public static final String UNKNOWN_SECTOR = "Unclassified";

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("companyName", companyName);
        row.put("sector", sector);
        row.put("industry", industry);
        row.put("exchange", exchange);
        row.put("indexName", indexName);
        return row;
    }
}
