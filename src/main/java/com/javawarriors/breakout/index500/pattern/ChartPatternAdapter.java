package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.bullish.ChartPatternDetector;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.IndicatorSnapshot;

import java.util.function.BiFunction;

/**
 * Exposes one of the existing multi-bar chart detectors as a {@link PatternDetector}.
 *
 * <p>Double Bottom, Cup &amp; Handle, Inverse Head &amp; Shoulders and the rest were already
 * implemented - with structural tests, confidence scoring and measured-move targets - for the
 * Bullish Stocks tab. Re-implementing them here would give the app two answers to "is this a double
 * bottom", which is worse than having one shared answer even if the shared one is imperfect.
 *
 * <p>Parameterised by a method reference rather than written out six times, because the six
 * adapters would otherwise be identical but for one call. The classes the spec names remain
 * addressable through {@link PatternRegistry}, which is where the list of patterns lives.
 */
public final class ChartPatternAdapter implements PatternDetector {

    private final String type;
    private final String displayName;
    private final BiFunction<IndicatorSnapshot, BullishConfig, ChartPattern> detector;
    private final BullishConfig cfg;

    public ChartPatternAdapter(String type, String displayName,
                               BiFunction<IndicatorSnapshot, BullishConfig, ChartPattern> detector,
                               BullishConfig cfg) {
        this.type = type;
        this.displayName = displayName;
        this.detector = detector;
        this.cfg = cfg;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        ChartPattern p;
        try {
            p = detector.apply(s, cfg);
        } catch (RuntimeException e) {
            // A malformed series must not take down the whole scan - see Index500AnalysisService.
            return PatternResult.absent(s.symbol, type, displayName);
        }
        if (p == null || !p.isPresent()) return PatternResult.absent(s.symbol, type, displayName);

        double price = s.price;
        double stop = p.invalidationLevel();
        double entry = Math.max(price, p.breakoutLevel());
        double risk = entry - stop;
        double reward = p.target() - entry;
        double rr = risk > 0 && reward > 0 ? reward / risk : Double.NaN;

        String confirmation = price > p.breakoutLevel() ? PatternResult.CONFIRMED
                : price >= p.resistance() * 0.97 ? PatternResult.WAIT_FOR_BREAKOUT
                : PatternResult.FORMING;

        return new PatternResult(s.symbol, type, displayName, true,
                // The shared detectors score confidence out of 100; this feature reports out of 10.
                p.confidence() / 10.0,
                p.startIndex() >= 0 && p.startIndex() < s.n ? s.bars.get(p.startIndex()).time() : null,
                stop, p.resistance(), p.breakoutLevel(), price, p.target(), stop, rr,
                confirmation, p.explanation());
    }

    /** The six shared chart patterns, each as its own detector. */
    public static ChartPatternAdapter doubleBottom(BullishConfig cfg) {
        return new ChartPatternAdapter("DOUBLE_BOTTOM", "Double Bottom", ChartPatternDetector::doubleBottom, cfg);
    }

    public static ChartPatternAdapter cupAndHandle(BullishConfig cfg) {
        return new ChartPatternAdapter("CUP_AND_HANDLE", "Cup & Handle", ChartPatternDetector::cupAndHandle, cfg);
    }

    public static ChartPatternAdapter inverseHeadAndShoulders(BullishConfig cfg) {
        return new ChartPatternAdapter("INVERSE_HEAD_AND_SHOULDERS", "Inverse Head & Shoulders",
                ChartPatternDetector::inverseHeadAndShoulders, cfg);
    }

    public static ChartPatternAdapter bullishFlag(BullishConfig cfg) {
        return new ChartPatternAdapter("BULL_FLAG", "Bullish Flag", ChartPatternDetector::bullFlag, cfg);
    }

    public static ChartPatternAdapter ascendingTriangle(BullishConfig cfg) {
        return new ChartPatternAdapter("ASCENDING_TRIANGLE", "Ascending Triangle",
                ChartPatternDetector::ascendingTriangle, cfg);
    }

    public static ChartPatternAdapter flatBase(BullishConfig cfg) {
        return new ChartPatternAdapter("FLAT_BASE", "Flat Base", ChartPatternDetector::flatBase, cfg);
    }
}
