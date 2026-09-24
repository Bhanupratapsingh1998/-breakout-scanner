package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NiftyUniverse;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.scan.ScanRunner;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scores one stock on the 100-point bullish assessment.
 *
 * <p>This used to own the Nifty 500 ranking behind the Bullish Stocks tab as well. That tab has
 * been removed, and the ranking with it; what survives is the single-symbol lookup, because My
 * Watchlist scores every followed stock through it - including stocks outside the index, which is
 * why it takes a symbol rather than reading from a completed scan.
 */
@Service
public class BullishStocksService {

    /** The window every daily scan uses, so a lookup is served from a warm cache where possible. */
    private static final String RANGE = "18mo";

    private final BullishConfig cfg;
    private final YahooDataSource source = new YahooDataSource();

    public BullishStocksService(BullishConfig cfg) {
        this.cfg = cfg;
    }

    /** Live single-symbol assessment, for the detail view and for symbols outside the index. */
    public Map<String, Object> analyzeOne(String rawSymbol) throws IOException {
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        ensureBenchmarks();
        List<Bar> nifty50 = BenchmarkSource.barsFor("NIFTY_50");
        List<Bar> nifty500 = BenchmarkSource.barsFor("NIFTY_500");
        MarketRegimeAnalyzer.Regime regime = MarketRegimeAnalyzer.analyze(nifty50, nifty500, cfg);

        List<Bar> bars = BarCache.daily(source, symbol, RANGE);
        Map<String, String> names = NseIndexSource.cached();

        BullishStockResult result = new BullishStockAnalyzer(cfg).analyze(
                symbol, displayName(symbol, names), ScanRunner.universeOf(symbol),
                NseIndexSource.sectorOf(symbol), bars, nifty50, nifty500, regime);

        Map<String, Object> row = result.toRow();
        row.put("marketRegime", regime.toRow());
        return row;
    }

    private void ensureBenchmarks() {
        if (BenchmarkSource.barsFor("NIFTY_500") == null && BenchmarkSource.barsFor("NIFTY_50") == null) {
            BenchmarkSource.refreshAll();
        }
    }

    private static String displayName(String symbol, Map<String, String> nifty500Names) {
        String fallback = symbol.replaceAll("\\.(NS|BO)$", "");
        return nifty500Names.getOrDefault(symbol, NiftyUniverse.NAMES.getOrDefault(symbol, fallback));
    }
}
