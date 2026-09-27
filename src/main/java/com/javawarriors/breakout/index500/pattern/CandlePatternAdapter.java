package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.intraday.CandlePatternDetector;
import com.javawarriors.breakout.intraday.CandlePatternDetector.CandlePattern;

/**
 * Exposes one of the shared single/multi-candle patterns as a {@link PatternDetector}.
 *
 * <p>Hammer and Bullish Engulfing already exist in the intraday detector, complete with the
 * prior-trend context test that separates a Hammer from a Hanging Man. Those run equally well on
 * daily candles, so this adapts rather than re-implements them.
 */
public final class CandlePatternAdapter implements PatternDetector {

    /** How far back the candle may have printed and still be actionable on a daily chart. */
    private static final int RECENT_BARS = 5;

    private final String type;
    private final String displayName;

    public CandlePatternAdapter(String type, String displayName) {
        this.type = type;
        this.displayName = displayName;
    }

    public static CandlePatternAdapter hammer() {
        return new CandlePatternAdapter("HAMMER", "Hammer");
    }

    public static CandlePatternAdapter bullishEngulfing() {
        return new CandlePatternAdapter("BULLISH_ENGULFING", "Bullish Engulfing");
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
        PatternResult none = PatternResult.absent(s.symbol, type, displayName);
        if (s.n < 20) return none;

        for (int i = s.n - 1; i >= Math.max(2, s.n - RECENT_BARS); i--) {
            CandlePattern found = CandlePatternDetector.detectAt(s.bars, i).stream()
                    .filter(p -> p.type().equals(type))
                    .findFirst().orElse(null);
            if (found == null) continue;

            int barsSince = s.n - 1 - i;
            double low = s.low[i];
            // Confirmation on a candle pattern is a later close above its high, which is exactly
            // what the reversal scan asks for too.
            boolean confirmed = false;
            for (int j = i + 1; j < s.n; j++) if (s.close[j] > s.high[i]) { confirmed = true; break; }

            double confidence = 4.5 + found.strength();                    // strength is 1-3
            if (s.volumeRatio >= 1.5) confidence += 1.0;
            if (confirmed) confidence += 1.0;
            if (s.lastRsi <= 45) confidence += 0.5;                        // fired while still oversold
            confidence = Math.min(9.5, confidence);

            double resistance = s.nearestResistanceAbove(s.price);
            double target = Double.isNaN(resistance) ? s.price + 3 * s.lastAtr : resistance;
            double stop = low - 0.5 * s.lastAtr;

            /*
             * Reward is measured from the entry a reader could actually take, not from today's
             * close, and the two are not the same thing once a pattern is a few sessions old.
             *
             * The old formula divided (target - price) by (price - stop) while the row displayed
             * the pattern candle's high as the breakout. On a stock that had since run up to just
             * under resistance that printed things like "breakout 742.83, target 807, R:R 0.01" -
             * three numbers from three different moments, describing no trade that exists. Worse,
             * when the pattern's own high WAS the nearest resistance, target and breakout came out
             * identical: buy at X, sell at X.
             *
             * Entry is therefore max(price, breakout) - the same convention ChartPatternAdapter
             * already used - and a target that is not above that entry is reported as absent
             * rather than as a ratio near zero. "There is no room to the next resistance" is a
             * real answer; "R:R 0.0:1" only looks like one.
             */
            TradeLevels levels = TradeLevels.of(s.price, s.high[i], stop, target, s.lastAtr);
            boolean roomAbove = levels.hasRoom();
            double rr = levels.riskReward();

            String note = found.description()
                    + String.format(" Printed %d session%s ago.", barsSince, barsSince == 1 ? "" : "s");
            if (!roomAbove) note += levels.noRoomNote();

            return new PatternResult(s.symbol, type, displayName, true, confidence,
                    s.bars.get(i).time(), low, Double.isNaN(resistance) ? Double.NaN : resistance,
                    s.high[i], s.price, levels.target(),
                    stop, rr,
                    confirmed ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_CONFIRMATION,
                    note);
        }
        return none;
    }
}
