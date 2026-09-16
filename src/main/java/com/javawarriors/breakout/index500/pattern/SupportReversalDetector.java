package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.candlestick.SupportLevelDetector;

/**
 * Price turning up off a support level that has actually been defended before.
 *
 * <p>The support itself comes from the existing {@link SupportLevelDetector}, which only calls a
 * level "support" when it has evidence - multiple historical touches, the lowest swing low in the
 * window, or a reaction off the 50/200 EMA. That evidence requirement is the whole point: every dip
 * has a low under it, and treating any of them as support would make this detector fire on
 * everything.
 *
 * <p>On top of that this adds the reversal itself: the low must be recent, price must have turned
 * back up off it, and it must still be close enough to the level for the level to be the reason.
 */
public final class SupportReversalDetector implements PatternDetector {

    /** How recently the reaction low must have formed. */
    private static final int RECENT_BARS = 20;

    /** How near the level price must still be, as a percentage, for it to be the operative level. */
    private static final double TOLERANCE_PCT = 4.0;

    @Override
    public String type() {
        return "SUPPORT_REVERSAL";
    }

    @Override
    public String displayName() {
        return "Support Reversal";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        if (s.n < 80) return none;

        // The lowest low of the recent window, and where it happened.
        int lowIdx = -1;
        double low = Double.MAX_VALUE;
        for (int i = Math.max(0, s.n - RECENT_BARS); i < s.n; i++) {
            if (s.low[i] < low) { low = s.low[i]; lowIdx = i; }
        }
        if (lowIdx < 0) return none;

        int barsSince = s.n - 1 - lowIdx;
        // Needs room to have turned, but not so long ago that the bounce is over.
        if (barsSince < 2 || barsSince > RECENT_BARS) return none;
        if (!(s.price > low)) return none;

        SupportLevelDetector.SupportResult support = SupportLevelDetector.nearestSupport(
                s.bars, lowIdx, low, TOLERANCE_PCT, s.lastEma50, s.lastEma200);
        if (support == null || support.distancePct() > TOLERANCE_PCT) return none;

        double bouncePct = (s.price / low - 1) * 100;
        if (bouncePct < 1.5) return none;                       // not yet a reaction
        double fromLevelPct = (s.price / support.level() - 1) * 100;
        if (fromLevelPct > 12) return none;                     // too far above to still be the reason

        double confidence = 5.0;
        if (support.touches() >= 3) confidence += 1.5;          // a level defended repeatedly
        else if (support.touches() == 2) confidence += 1.0;
        if (s.volumeRatio >= 1.5) confidence += 1.0;            // bought on participation
        if (s.lastRsi >= 35 && s.lastRsi <= 60) confidence += 1.0;   // turning out of oversold
        if (s.price > s.lastEma20) confidence += 1.0;           // the bounce has legs
        confidence = Math.min(9.5, confidence);

        double resistance = s.nearestResistanceAbove(s.price);
        double target = Double.isNaN(resistance) ? s.price + 3 * s.lastAtr : resistance;
        double stop = low - 0.5 * s.lastAtr;
        double risk = s.price - stop;
        double rr = risk > 0 ? (target - s.price) / risk : Double.NaN;

        return new PatternResult(s.symbol, type(), displayName(), true, confidence,
                s.bars.get(lowIdx).time(), support.level(),
                Double.isNaN(resistance) ? Double.NaN : resistance,
                Double.isNaN(resistance) ? Double.NaN : resistance, s.price, target, stop, rr,
                s.price > s.lastEma20 ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_CONFIRMATION,
                String.format("Turned up %.1f%% off %.2f, a level with %s, %d sessions ago.",
                        bouncePct, support.level(), support.evidence(), barsSince));
    }
}
