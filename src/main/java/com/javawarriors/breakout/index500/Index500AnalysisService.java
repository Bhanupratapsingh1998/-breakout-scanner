package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.candlestick.SupportLevelDetector;
import com.javawarriors.breakout.index500.pattern.PatternResult;
import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.NseIndexSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Orchestrates the Index 500 analysis: fetch once, compute everything, keep the result.
 *
 * <p>The performance rule the spec is firmest about is honoured here. Each symbol's bars are
 * fetched once through the shared {@link BarCache}, one {@link IndicatorSnapshot} computes every
 * indicator from them, and all fifteen detectors read that snapshot. There is no per-indicator or
 * per-pattern call to the data provider.
 *
 * <p>Filtering does <b>not</b> re-scan. The analysed universe is held in memory and the controller
 * filters over it, so changing sector, pattern or an RSI bound is instant - which is what makes the
 * sector-then-pattern workflow usable at all.
 *
 * <p>One symbol failing never stops the rest: it is recorded as
 * {@link Index500Analysis#DATA_UNAVAILABLE} with a reason, logged with symbol and timestamp, and
 * the scan continues.
 */
@Service
public class Index500AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(Index500AnalysisService.class);

    private final Index500Config cfg;
    private final SectorService sectors;
    private final PatternAnalysisService patternAnalysis;
    private final OpportunityScoreService scoring;
    private final YahooDataSource source = new YahooDataSource();

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<String> progress = new AtomicReference<>(null);
    private final AtomicReference<List<Index500Analysis>> lastRun = new AtomicReference<>(null);
    private final AtomicReference<Map<String, Object>> lastSummary = new AtomicReference<>(null);
    private final AtomicReference<String> generatedAt = new AtomicReference<>(null);

    public Index500AnalysisService(Index500Config cfg, SectorService sectors,
                                   PatternAnalysisService patternAnalysis,
                                   OpportunityScoreService scoring) {
        this.cfg = cfg;
        this.sectors = sectors;
        this.patternAnalysis = patternAnalysis;
        this.scoring = scoring;
    }

    public boolean triggerScan() {
        if (!running.compareAndSet(false, true)) return false;
        Thread worker = new Thread(() -> {
            try {
                List<Index500Analysis> rows = runScan(progress::set);
                lastRun.set(rows);
                generatedAt.set(Instant.now().toString());

                long analysed = rows.stream().filter(Index500Analysis::analysed).count();
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("universeSize", rows.size());
                summary.put("analysed", analysed);
                summary.put("unavailable", rows.size() - analysed);
                summary.put("generatedAt", generatedAt.get());
                lastSummary.set(summary);
            } catch (Exception e) {
                log.error("Index 500 analysis failed", e);
                lastSummary.set(Map.of("error", String.valueOf(e.getMessage())));
            } finally {
                progress.set(null);
                running.set(false);
            }
        }, "index500-scan-runner");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    public Map<String, Object> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("running", running.get());
        body.put("progress", progress.get());
        body.put("lastResult", lastSummary.get());
        return body;
    }

    public List<Index500Analysis> lastRun() {
        return lastRun.get();
    }

    public String generatedAt() {
        return generatedAt.get();
    }

    List<Index500Analysis> runScan(Consumer<String> onProgress) {
        try {
            NseIndexSource.refreshNifty500();
        } catch (IOException e) {
            log.warn("Nifty 500 list unavailable, using cached membership: {}", e.getMessage());
        }
        BenchmarkSource.refreshAll();
        double benchmark6mPct = benchmarkSixMonthReturnPct();

        List<StockMetadata> universe = sectors.universe();
        List<Index500Analysis> out = new ArrayList<>();

        for (int i = 0; i < universe.size(); i++) {
            StockMetadata meta = universe.get(i);
            if (onProgress != null) {
                onProgress.accept(meta.symbol() + " (" + (i + 1) + "/" + universe.size() + ")");
            }
            boolean cached = BarCache.peek(meta.symbol(), cfg.getRange()) != null;
            try {
                List<Bar> bars = BarCache.daily(source, meta.symbol(), cfg.getRange());
                out.add(analyse(meta, bars, benchmark6mPct));
            } catch (Exception e) {
                // One symbol must never stop the universe. Record it, log it, carry on.
                log.warn("Index 500: skipping {} at {} - {}", meta.symbol(), Instant.now(), e.toString());
                out.add(Index500Analysis.unavailable(meta,
                        e.getClass().getSimpleName() + ": " + e.getMessage()));
            }
            if (!cached) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        return out;
    }

    /** Analyses one stock from bars already in hand. Package-private so tests can drive it directly. */
    Index500Analysis analyse(StockMetadata meta, List<Bar> bars, double benchmark6mPct) {
        if (bars == null || bars.size() < cfg.getMinBars()) {
            return Index500Analysis.unavailable(meta,
                    "need >= " + cfg.getMinBars() + " bars, got " + (bars == null ? 0 : bars.size()));
        }
        IndicatorSnapshot s = IndicatorSnapshot.of(meta.symbol(), bars);

        List<PatternResult> patterns = patternAnalysis.detect(s);
        PatternResult best = patternAnalysis.best(patterns);
        String[] status = patternAnalysis.classify(s, patterns, best, cfg);
        OpportunityScore score = scoring.score(s, patterns, best, benchmark6mPct, cfg);

        double high52w = s.highestHigh(IndicatorSnapshot.BARS_52W);
        double low52w = s.lowestLow(IndicatorSnapshot.BARS_52W);
        double return6mPct = pct(s.return6m);
        double return1yPct = pct(s.trailingReturn(IndicatorSnapshot.BARS_52W));

        SupportLevelDetector.SupportResult support = SupportLevelDetector.nearestSupport(
                bars, s.n, s.price, 5.0, s.lastEma50, s.lastEma200);
        double resistance = s.nearestResistanceAbove(s.price);

        return new Index500Analysis(meta, true, status[0], status[1], null,
                // The last session's move, measured the same way as the other four windows rather
                // than off prevClose, so all five returns share one definition and one NaN rule.
                s.price, pct(s.trailingReturn(1)), pct(s.return1m), pct(s.return3m), return6mPct,
                return1yPct,
                Double.isNaN(return6mPct) ? Double.NaN : -return6mPct,
                high52w > 0 ? (s.price / high52w - 1) * 100 : Double.NaN,
                low52w > 0 ? (s.price / low52w - 1) * 100 : Double.NaN,
                high52w, low52w,
                s.lastEma20, s.lastEma50, s.lastEma200, emaStatus(s),
                s.lastRsi, s.lastAdx, s.lastAtr, s.volume[s.n - 1], s.avgVolume20, s.volumeRatio,
                support == null ? Double.NaN : support.level(), resistance,
                patterns, best, score);
    }

    /** A compact read of where price sits in its own moving-average stack. */
    static String emaStatus(IndicatorSnapshot s) {
        if (s.price > s.lastEma20 && s.lastEma20 > s.lastEma50 && s.lastEma50 > s.lastEma200) {
            return "ALIGNED UP";
        }
        if (s.price > s.lastEma50 && s.price > s.lastEma200) return "ABOVE 50 & 200";
        if (s.price > s.lastEma50) return "ABOVE 50";
        if (s.price > s.lastEma20) return "ABOVE 20";
        if (s.price < s.lastEma200 && s.lastEma50 < s.lastEma200) return "BELOW ALL";
        return "MIXED";
    }

    /** Nifty 500's own six-month return, the yardstick the relative-strength component uses. */
    double benchmarkSixMonthReturnPct() {
        List<Bar> bench = BenchmarkSource.barsFor("NIFTY_500");
        if (bench == null) bench = BenchmarkSource.barsFor("NIFTY_50");
        if (bench == null || bench.size() <= IndicatorSnapshot.BARS_6M) return Double.NaN;
        double[] close = new double[bench.size()];
        for (int i = 0; i < close.length; i++) close[i] = bench.get(i).close();
        return pct(com.javawarriors.breakout.breakout.BreakoutAnalyzer
                .trailingReturn(close, IndicatorSnapshot.BARS_6M));
    }

    private static double pct(double fraction) {
        return Double.isNaN(fraction) ? Double.NaN : fraction * 100;
    }
}
