package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.bullish.IndicatorSnapshot;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The list of patterns this feature knows, and the only place it is written down.
 *
 * <p>Adding a pattern is adding a class and one line here - nothing else in the feature enumerates
 * patterns, so the filter dropdown, the API's accepted values and what actually runs cannot drift
 * apart. The order below is the order the dropdown shows.
 *
 * <p>Detection runs every detector against one {@link IndicatorSnapshot}. That is the expensive
 * thing done cheaply: the indicators are computed once per stock and shared, so fourteen detectors
 * cost fourteen passes over arrays already in memory, not fourteen fetches.
 */
@Component
public class PatternRegistry {

    private final List<PatternDetector> detectors;

    public PatternRegistry(BullishConfig cfg) {
        this.detectors = List.of(
                ChartPatternAdapter.doubleBottom(cfg),
                ChartPatternAdapter.cupAndHandle(cfg),
                ChartPatternAdapter.inverseHeadAndShoulders(cfg),
                new FallingWedgeDetector(),
                ChartPatternAdapter.bullishFlag(cfg),
                ChartPatternAdapter.ascendingTriangle(cfg),
                ChartPatternAdapter.flatBase(cfg),
                CandlePatternAdapter.bullishEngulfing(),
                CandlePatternAdapter.hammer(),
                new HigherHighHigherLowDetector(),
                EmaReclaimDetector.ema50(),
                EmaReclaimDetector.ema200(),
                new BreakoutDetector(cfg),
                new BreakoutRetestDetector(cfg),
                new SupportReversalDetector());
    }

    public List<PatternDetector> detectors() {
        return detectors;
    }

    /** Type -> display name, for the filter dropdown. */
    public Map<String, String> catalogue() {
        Map<String, String> out = new LinkedHashMap<>();
        for (PatternDetector d : detectors) out.put(d.type(), d.displayName());
        return out;
    }

    public boolean isKnownType(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) return true;
        return detectors.stream().anyMatch(d -> d.type().equalsIgnoreCase(type));
    }

    /**
     * Every pattern found on this stock, strongest first.
     *
     * <p>A detector that throws is skipped rather than allowed to abort the stock: one malformed
     * series must not cost the other fourteen readings, let alone the rest of the universe.
     */
    public List<PatternResult> detectAll(IndicatorSnapshot s) {
        List<PatternResult> found = new ArrayList<>();
        for (PatternDetector d : detectors) {
            try {
                PatternResult r = d.detect(s);
                if (r != null && r.detected()) found.add(r);
            } catch (RuntimeException ignored) {
                // Skip this detector for this stock; the others still run.
            }
        }
        found.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return found;
    }
}
