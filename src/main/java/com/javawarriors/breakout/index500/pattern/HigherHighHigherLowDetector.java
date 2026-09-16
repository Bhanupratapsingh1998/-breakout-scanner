package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.bullish.PriceStructure;

/**
 * Swing structure that has turned up: higher highs and higher lows.
 *
 * <p>Reuses {@link PriceStructure}, so this feature reads structure exactly as the bullish ranking
 * does. On a screen built around stocks that have fallen, this is the earliest structural evidence
 * that the decline has actually ended rather than paused - a downtrend by definition cannot print
 * both a higher high and a higher low.
 */
public final class HigherHighHigherLowDetector implements PatternDetector {

    @Override
    public String type() {
        return "HIGHER_HIGH_HIGHER_LOW";
    }

    @Override
    public String displayName() {
        return "Higher High / Higher Low";
    }

    @Override
    public PatternResult detect(IndicatorSnapshot s) {
        PatternResult none = PatternResult.absent(s.symbol, type(), displayName());
        if (s.n < 60 || s.swingHighs.size() < 2 || s.swingLows.size() < 2) return none;
        if (!"HH+HL".equals(PriceStructure.classify(s))) return none;

        double lastLow = s.low[s.swingLows.get(s.swingLows.size() - 1)];
        double lastHigh = s.high[s.swingHighs.get(s.swingHighs.size() - 1)];
        double priorLow = s.low[s.swingLows.get(s.swingLows.size() - 2)];

        double confidence = 5.5;
        if (s.price > s.lastEma20 && s.lastEma20 > s.lastEma50) confidence += 1.5;
        if (s.lastAdx >= 20) confidence += 1.0;
        if (priorLow > 0 && (lastLow / priorLow - 1) >= 0.03) confidence += 1.0;   // decisively higher
        if (s.volumeRatio >= 1.2) confidence += 0.5;
        confidence = Math.min(9.5, confidence);

        double target = lastHigh + (lastHigh - lastLow);
        double stop = lastLow - 0.5 * s.lastAtr;
        double risk = s.price - stop;
        double rr = risk > 0 ? (target - s.price) / risk : Double.NaN;

        return new PatternResult(s.symbol, type(), displayName(), true, confidence,
                s.bars.get(s.swingLows.get(s.swingLows.size() - 1)).time(),
                lastLow, lastHigh, lastHigh, s.price, target, stop, rr,
                s.price > lastHigh ? PatternResult.CONFIRMED : PatternResult.WAIT_FOR_BREAKOUT,
                String.format("Last swing low %.2f is above the one before it, under a higher high at"
                        + " %.2f - the structure has turned.", lastLow, lastHigh));
    }
}
