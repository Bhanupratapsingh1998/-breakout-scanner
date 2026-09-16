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
            double risk = s.price - stop;
            double rr = risk > 0 ? (target - s.price) / risk : Double.NaN;

            return new PatternResult(s.symbol, type, displayName, true, confidence,
                    s.bars.get(i).time(), low, Double.isNaN(resistance) ? Double.NaN : resistance,
                    s.high[i], s.price, target, stop, rr,
                    confirmed ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_CONFIRMATION,
                    found.description() + String.format(" Printed %d session%s ago.",
                            barsSince, barsSince == 1 ? "" : "s"));
        }
        return none;
    }
}
