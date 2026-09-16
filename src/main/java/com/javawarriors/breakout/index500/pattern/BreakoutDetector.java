package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer;
import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.IndicatorSnapshot;

/**
 * A close clear of a real resistance level - 52-week high, six-month high, or the top of a
 * consolidation.
 *
 * <p>Adapts the existing {@link BreakoutStageAnalyzer}, which already refuses to call an intraday
 * spike a breakout: the level must be cleared on a close, by a confirmation buffer rather than a
 * tick, and the breakout bar's volume and closing position are scored separately from the fact of
 * the break.
 *
 * <p>{@link BreakoutRetestDetector} is the sibling that fires once price comes back to test the
 * level it cleared.
 */
public final class BreakoutDetector implements PatternDetector {

    private final BullishConfig cfg;

    public BreakoutDetector(BullishConfig cfg) {
        this.cfg = cfg;
    }

    @Override
    public String type() {
        return "BREAKOUT";
    }

    @Override
    public String displayName() {
        return "Breakout";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        BreakoutStage b = BreakoutStageAnalyzer.analyze(s, ChartPattern.none(), cfg);
        if (b.failed() || !b.hasLevel()) return none;
        // A pending breakout is reported as FORMING; only a cleared level counts as detected.
        if (!b.confirmed() && !b.nearBreakout()) return none;

        double level = b.level();
        double stop = level - 0.5 * s.lastAtr;
        double entry = b.confirmed() ? s.price : b.confirmedLevel();
        double resistance = s.nearestResistanceAbove(entry);
        double target = Double.isNaN(resistance) ? entry + 3 * s.lastAtr : resistance;
        double risk = entry - stop;
        double rr = risk > 0 ? (target - entry) / risk : Double.NaN;

        // The shared analyzer scores breakout quality out of 10 already; reuse it as confidence.
        double confidence = b.confirmed() ? Math.max(4.0, b.points()) : 4.0;

        return new PatternResult(s.symbol, type(), displayName(), true, Math.min(9.5, confidence),
                b.breakoutBarIndex() >= 0 ? s.bars.get(b.breakoutBarIndex()).time() : null,
                stop, level, b.confirmedLevel(), s.price, target, stop, rr,
                b.confirmed() ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_BREAKOUT,
                b.explanation());
    }
}
