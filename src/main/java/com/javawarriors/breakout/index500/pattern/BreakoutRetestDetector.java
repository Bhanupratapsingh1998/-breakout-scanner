package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer;
import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.IndicatorSnapshot;

/**
 * Price back at a level it has already broken, with that old resistance now underneath it.
 *
 * <p>Distinct from {@link BreakoutDetector} because it is a different trade: the break already
 * happened, and what is on offer is the second entry at a better price. A retest that has failed -
 * price closing back below the level rather than holding it - is explicitly not reported here; the
 * shared analyzer classifies that as a failed breakout and it is excluded.
 */
public final class BreakoutRetestDetector implements PatternDetector {

    private final BullishConfig cfg;

    public BreakoutRetestDetector(BullishConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String type() {
        return "BREAKOUT_RETEST";
    }

    @Override
    public String displayName() {
        return "Breakout Retest";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        BreakoutStage b = BreakoutStageAnalyzer.analyze(s, ChartPattern.none(), cfg);
        if (!b.confirmed() || b.failed() || !b.hasLevel()) return none;
        // Still inside the retest band above the level, and at least a session after the break.
        if (b.barsSinceBreakout() < 1) return none;
        if (b.distancePct() > cfg.getRetestBand() * 100) return none;

        double level = b.level();
        double stop = level - 0.5 * s.lastAtr;
        double resistance = s.nearestResistanceAbove(s.price);
        double target = Double.isNaN(resistance) ? s.price + 3 * s.lastAtr : resistance;
        double risk = s.price - stop;
        double rr = risk > 0 ? (target - s.price) / risk : Double.NaN;

        double confidence = 6.0;
        if (s.price > s.lastEma20) confidence += 1.0;              // holding above the fast mean
        if (s.volumeRatio < 1.0) confidence += 0.5;                // a quiet retest is the healthy kind
        if (b.barsSinceBreakout() >= 3) confidence += 1.0;         // the break has survived a few days
        if (s.lastRsi >= 45) confidence += 0.5;
        confidence = Math.min(9.5, confidence);

        return new PatternResult(s.symbol, type(), displayName(), true, confidence,
                b.breakoutBarIndex() >= 0 ? s.bars.get(b.breakoutBarIndex()).time() : null,
                level, resistance, level, s.price, target, stop, rr,
                PatternResult.CONFIRMED,
                String.format("Broke %.2f %d sessions ago and is back within %.1f%% of it, with the old"
                        + " resistance now acting as support.", level, b.barsSinceBreakout(), b.distancePct()));
    }
}
