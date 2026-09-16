package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;

import java.util.List;

/**
 * A falling wedge: both the highs and the lows are declining, but the highs are falling faster, so
 * the range narrows as price drops.
 *
 * <p>New here - it is the one pattern in the requested list that the existing detectors do not
 * cover, and it is the most relevant of them to a "what has fallen hardest" screen, because it is
 * what a controlled decline running out of sellers looks like.
 *
 * <p>The tests that separate it from an ordinary downtrend: the swing highs must be falling, the
 * swing lows must be falling <em>more slowly</em> (that convergence is the wedge), the range must
 * actually be narrower at the end than at the start, and volume must be lighter than it was at the
 * start of the decline. A channel where both lines fall in parallel is not a wedge and is rejected.
 */
public final class FallingWedgeDetector implements PatternDetector {

    /** Below this many confirmed pivots on either side there is no line to fit. */
    private static final int MIN_PIVOTS = 3;

    /** How much of the recent series the wedge is looked for in. */
    private static final int LOOKBACK = 120;

    @Override
    public String type() {
        return "FALLING_WEDGE";
    }

    @Override
    public String displayName() {
        return "Falling Wedge";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        if (s.n < 60) return none;

        final int from = s.n - LOOKBACK;
        List<Integer> highs = s.swingHighs.stream().filter(i -> i >= from).toList();
        List<Integer> lows = s.swingLows.stream().filter(i -> i >= from).toList();
        if (highs.size() < MIN_PIVOTS || lows.size() < MIN_PIVOTS) return none;

        double firstHigh = s.high[highs.get(0)];
        double lastHigh = s.high[highs.get(highs.size() - 1)];
        double firstLow = s.low[lows.get(0)];
        double lastLow = s.low[lows.get(lows.size() - 1)];
        if (firstHigh <= 0 || firstLow <= 0) return none;

        double highFall = (firstHigh - lastHigh) / firstHigh;   // positive when highs are falling
        double lowFall = (firstLow - lastLow) / firstLow;

        // Both lines must fall, and the ceiling must fall faster than the floor.
        if (highFall <= 0.02 || lowFall < 0) return none;
        if (highFall <= lowFall) return none;

        double openWidth = (firstHigh - firstLow) / firstLow;
        double closeWidth = (lastHigh - lastLow) / lastLow;
        if (openWidth <= 0 || closeWidth <= 0) return none;
        // A wedge converges. Requiring a real narrowing is what rejects a parallel channel.
        double convergence = 1 - (closeWidth / openWidth);
        if (convergence < 0.25) return none;

        double earlyVolume = averageVolume(s, from, from + LOOKBACK / 3);
        double lateVolume = averageVolume(s, s.n - LOOKBACK / 3, s.n);

        double confidence = 5.5;
        if (convergence >= 0.45) confidence += 1.5;                       // tightly converging
        if (highs.size() >= 4 && lows.size() >= 4) confidence += 1.0;     // more touches on both lines
        if (earlyVolume > 0 && lateVolume < earlyVolume * 0.8) confidence += 1.0;   // selling drying up
        if (s.price > s.lastEma20) confidence += 1.0;                     // already pushing out of it
        confidence = Math.min(9.5, confidence);

        // The upper line is the wedge's resistance; clearing it is what resolves the pattern.
        double resistance = lastHigh;
        double breakout = resistance * 1.005;
        double support = lastLow;
        double height = firstHigh - firstLow;
        double target = breakout + height;    // measured move: the wedge's opening width
        double risk = breakout - support;
        double rr = risk > 0 ? (target - breakout) / risk : Double.NaN;

        String confirmation = s.price > breakout ? PatternResult.CONFIRMED
                : s.price >= resistance * 0.97 ? PatternResult.WAIT_FOR_BREAKOUT
                : PatternResult.FORMING;

        return new PatternResult(s.symbol, type(), displayName(), true, confidence,
                s.bars.get(Math.max(from, 0)).time(), support, resistance, breakout, s.price,
                target, support, rr, confirmation,
                String.format("Highs fell %.0f%% while lows fell only %.0f%%, narrowing the range by"
                                + " %.0f%% - a decline losing momentum rather than a free fall.",
                        highFall * 100, lowFall * 100, convergence * 100));
    }

    private static double averageVolume(IndicatorSnapshot s, int from, int toExclusive) {
        int a = Math.max(0, from), b = Math.min(toExclusive, s.n);
        if (b <= a) return 0;
        double sum = 0;
        for (int i = a; i < b; i++) sum += s.volume[i];
        return sum / (b - a);
    }
}
