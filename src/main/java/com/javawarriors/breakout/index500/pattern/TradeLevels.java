package com.javawarriors.breakout.index500.pattern;

/**
 * Entry, risk and reward, measured the same way by every detector.
 *
 * <h2>The bug this exists to prevent</h2>
 *
 * <p>Detectors used to compute reward as {@code (target - price)} while the row they produced
 * displayed a <em>historical</em> breakout level. On a stock that had since run up to just under
 * its next resistance that printed rows like "breakout 742.83, target 807.00, R:R 0.01" - three
 * numbers from three different moments, describing no trade that exists. Measured live across 780
 * patterns, a quarter of them quoted a ratio below 0.3:1 for this reason, and 141 quoted a target
 * exactly equal to their own breakout: buy at X, sell at X.
 *
 * <p>Two rules fix the class of problem rather than its instances:
 *
 * <ol>
 *   <li><b>Entry is what a reader could actually take</b> - {@code max(price, breakoutLevel)}. A
 *       level the stock has already passed is not an entry, and a level it has not reached yet is
 *       not today's price.</li>
 *   <li><b>A target that is not meaningfully above the entry is absent, not zero.</b> "There is no
 *       room to the next resistance" is a real answer. "R:R 0.0:1" only looks like one, and invites
 *       the reader to treat a non-setup as a bad setup.</li>
 * </ol>
 *
 * <p>"Meaningfully" is measured in the stock's own ATR rather than a percentage, because a rupee of
 * headroom means something different on a 50-rupee stock and a 5,000-rupee one. An objective inside
 * half an average day's range is noise: price covers that distance without the pattern doing
 * anything. Live, that distinction is what separates a usable target from
 * "target 807.00, price 806.00, R:R 0.01".
 */
public record TradeLevels(double entry, double target, double riskReward, boolean hasRoom) {

    /** An objective closer than this many ATRs above the entry is not an objective. */
    private static final double MIN_REWARD_IN_ATR = 0.5;

    /**
     * @param price         the last close
     * @param breakoutLevel the level that triggers the pattern, or NaN when it has none
     * @param stop          where the pattern would be proved wrong
     * @param target        the first objective above, before this check
     * @param atr           the stock's average true range, which sets how much headroom counts
     */
    public static TradeLevels of(double price, double breakoutLevel, double stop, double target,
                                 double atr) {
        double entry = Double.isNaN(breakoutLevel) ? price : Math.max(price, breakoutLevel);
        double risk = entry - stop;
        double reward = target - entry;
        double floor = Double.isNaN(atr) || atr <= 0 ? 0 : MIN_REWARD_IN_ATR * atr;
        boolean room = risk > 0 && reward > floor && !Double.isNaN(target);

        return new TradeLevels(entry,
                room ? target : Double.NaN,
                room ? reward / risk : Double.NaN,
                room);
    }

    /** Appended to a pattern's explanation when there is nothing above the entry to aim at. */
    public String noRoomNote() {
        return " Its next resistance sits on top of the entry, so there is no room to a target"
                + " from here.";
    }
}
