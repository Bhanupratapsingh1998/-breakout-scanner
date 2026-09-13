package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.marketdata.BarCache;
import com.javawarriors.breakout.marketdata.BenchmarkSource;
import com.javawarriors.breakout.marketdata.YahooDataSource;
import com.javawarriors.breakout.model.Bar;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Historical evaluation of the bullish engine's own signals on one stock.
 *
 * <p>Separate from {@code /api/backtest}, which tests the breakout scanner's condition - the two
 * engines make different calls and conflating their results would be meaningless.
 *
 * <p>Several horizons come back together because a signal that works held for a week and fails
 * held for a month is a different instrument from one that only pays over a month.
 */
@RestController
@RequestMapping("/api/bullish-stocks")
public class BullishBacktestController {

    /** Re-running the whole engine per bar is expensive; this bounds one request's work. */
    private static final Set<String> ALLOWED_RANGES = Set.of("2y", "5y", "10y");

    private static final int[] DEFAULT_HORIZONS = {10, 20, 40};

    private final BullishConfig cfg;
    private final YahooDataSource source = new YahooDataSource();

    public BullishBacktestController(BullishConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * {@code GET /api/bullish-stocks/backtest?symbol=DIXON.NS[&range=5y][&condition=buyNow][&step=1]}
     *
     * <p>{@code condition} accepts {@code buyNow}, any trade status
     * ({@code waitForRetest}, {@code waitForPullback}), {@code tradeable}, {@code score70}, or a
     * pattern type such as {@code BULL_FLAG}.
     */
    @GetMapping("/backtest")
    public ResponseEntity<?> backtest(@RequestParam(value = "symbol", required = false) String rawSymbol,
                                      @RequestParam(value = "range", required = false) String rawRange,
                                      @RequestParam(value = "condition", required = false) String rawCondition,
                                      @RequestParam(value = "step", required = false) Integer rawStep,
                                      @RequestParam(value = "trades", required = false) Boolean includeTrades) {
        if (rawSymbol == null || rawSymbol.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "missing ?symbol="));
        }
        String symbol = rawSymbol.trim().toUpperCase();
        if (!symbol.contains(".")) symbol += ".NS";

        String range = rawRange == null || rawRange.isBlank() ? "5y" : rawRange.trim().toLowerCase();
        if (!ALLOWED_RANGES.contains(range)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "unsupported ?range=" + rawRange + " (allowed: " + ALLOWED_RANGES + ")"));
        }

        String conditionName = rawCondition == null || rawCondition.isBlank() ? "buyNow" : rawCondition.trim();
        Predicate<BullishStockResult> condition = resolve(conditionName);
        if (condition == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "unsupported ?condition=" + rawCondition));
        }

        int step = rawStep == null ? 1 : Math.max(1, Math.min(20, rawStep));

        try {
            List<Bar> bars = BarCache.daily(source, symbol, range);
            int longest = DEFAULT_HORIZONS[DEFAULT_HORIZONS.length - 1];
            if (bars.size() <= BullishBacktester.WARMUP_BARS + longest) {
                return ResponseEntity.badRequest().body(Map.of("error", symbol + ": only " + bars.size()
                        + " bars in " + range + "; need more than "
                        + (BullishBacktester.WARMUP_BARS + longest) + " before any signal counts"));
            }

            if (BenchmarkSource.barsFor("NIFTY_500") == null) BenchmarkSource.refreshAll();
            List<Bar> nifty50 = BenchmarkSource.barsFor("NIFTY_50");
            List<Bar> nifty500 = BenchmarkSource.barsFor("NIFTY_500");

            List<Map<String, Object>> horizons = new ArrayList<>();
            for (int h : DEFAULT_HORIZONS) {
                horizons.add(BullishBacktester
                        .run(symbol, bars, nifty50, nifty500, condition, conditionName, h, step, cfg)
                        .toRow(Boolean.TRUE.equals(includeTrades)));
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("symbol", symbol);
            payload.put("range", range);
            payload.put("condition", conditionName);
            payload.put("step", step);
            payload.put("bars", bars.size());
            payload.put("warmupBars", BullishBacktester.WARMUP_BARS);
            payload.put("horizons", horizons);
            payload.put("note", "Past signal behaviour on this stock only. Not a forecast, and not a"
                    + " claim about any future trade.");
            return ResponseEntity.ok(payload);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "backtest failed for " + symbol + ": " + e.getMessage()));
        }
    }

    private Predicate<BullishStockResult> resolve(String name) {
        return switch (name) {
            case "buyNow" -> BullishBacktester.buyNow();
            case "waitForRetest" -> BullishBacktester.withStatus(PullbackAnalyzer.WAIT_FOR_RETEST);
            case "waitForPullback" -> BullishBacktester.withStatus(PullbackAnalyzer.WAIT_FOR_PULLBACK);
            case "waitForBreakout" -> BullishBacktester.withStatus(PullbackAnalyzer.WAIT_FOR_BREAKOUT);
            case "tradeable" -> r -> r.setup().tradeable();
            case "score70" -> BullishBacktester.scoreAtLeast(70);
            case "score85" -> BullishBacktester.scoreAtLeast(85);
            case "BULL_FLAG", "CUP_AND_HANDLE", "DOUBLE_BOTTOM", "ASCENDING_TRIANGLE",
                 "FLAT_BASE", "INVERSE_HEAD_AND_SHOULDERS" -> BullishBacktester.withPattern(name);
            default -> null;
        };
    }
}
