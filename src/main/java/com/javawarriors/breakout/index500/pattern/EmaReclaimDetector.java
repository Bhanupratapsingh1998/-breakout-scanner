package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;

/**
 * Price closing back above a moving average it had been trading below.
 *
 * <p>Not a shape - an event, and the one that most often marks the turn in a stock that has fallen
 * hard. A reclaim only counts if price was genuinely below the average first: a stock that has sat
 * above its 50 EMA for months is not "reclaiming" anything, and this detector rejects it.
 *
 * <p>Parameterised by which average, because the 50 and 200 reclaims are the same test on a
 * different series and two near-identical classes would be worse than one with a period.
 */
public final class EmaReclaimDetector implements PatternDetector {

    /** Sessions the reclaim must have happened within to still be news. */
    private static final int RECENT_BARS = 15;

    /** Sessions before that which price must have spent mostly below the average. */
    private static final int BELOW_LOOKBACK = 40;

    /** Fraction of that window that must have been spent below, so one dip does not qualify. */
    private static final double BELOW_SHARE = 0.6;

    private final int period;

    public EmaReclaimDetector(int period) {
        this.period = period;
    }

    public static EmaReclaimDetector ema50() {
        return new EmaReclaimDetector(50);
    }

    public static EmaReclaimDetector ema200() {
        return new EmaReclaimDetector(200);
    }

    @Override
    public String type() {
        return "EMA" + period + "_RECLAIM";
    }

    @Override
    public String displayName() {
        return "EMA" + period + " Reclaim";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        double[] ema = period == 200 ? s.ema200 : s.ema50;
        if (s.n < BELOW_LOOKBACK + RECENT_BARS + period) return none;
        if (!(s.price > ema[s.n - 1])) return none;   // must be above it now

        // Find the most recent bar that crossed from below to above.
        int crossed = -1;
        for (int i = s.n - 1; i >= s.n - RECENT_BARS; i--) {
            if (s.close[i] > ema[i] && s.close[i - 1] <= ema[i - 1]) { crossed = i; break; }
        }
        if (crossed < 0) return none;

        // It is only a reclaim if price had really been under the average beforehand.
        int below = 0;
        int from = Math.max(0, crossed - BELOW_LOOKBACK);
        for (int i = from; i < crossed; i++) if (s.close[i] < ema[i]) below++;
        if (below < (crossed - from) * BELOW_SHARE) return none;

        int barsSince = s.n - 1 - crossed;
        double reclaimLevel = ema[crossed];

        double confidence = 5.5;
        if (period == 200) confidence += 1.0;                                  // the more significant line
        if (barsSince >= 2 && barsSince <= 8) confidence += 1.0;               // held, but still fresh
        if (s.volumeRatio >= 1.5) confidence += 1.0;                           // reclaimed on participation
        if (s.lastRsi >= 45 && s.lastRsi <= 65) confidence += 0.8;             // momentum turning, not spent
        if (s.price > s.lastEma20) confidence += 0.7;
        confidence = Math.min(9.5, confidence);

        // The average it reclaimed is the level that now has to hold.
        double support = ema[s.n - 1];
        double resistance = s.nearestResistanceAbove(s.price);
        double target = Double.isNaN(resistance) ? s.price + 3 * s.lastAtr : resistance;
        double stop = support - 0.5 * s.lastAtr;
        double risk = s.price - stop;
        double rr = risk > 0 ? (target - s.price) / risk : Double.NaN;

        return new PatternResult(s.symbol, type(), displayName(), true, confidence,
                s.bars.get(crossed).time(), support, Double.isNaN(resistance) ? Double.NaN : resistance,
                reclaimLevel, s.price, target, stop, rr,
                barsSince >= 2 ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_CONFIRMATION,
                String.format("Closed back above its %d EMA %d session%s ago after spending most of the"
                                + " prior %d below it.",
                        period, barsSince, barsSince == 1 ? "" : "s", BELOW_LOOKBACK));
    }
}
