package com.javawarriors.breakout.candlestick;

/**
 * A detected candlestick pattern — a structured object rather than a boolean, so the API can
 * expose exactly what was found (type, the candles involved, the price levels) instead of just
 * a yes/no. `startIndex`/`endIndex` are bar indices into the source series (inclusive); single
 * -candle patterns have startIndex == endIndex.
 *
 * `strength` is a 0-1 shape metric relative to the pattern's own textbook definition (e.g. how
 * long the lower wick is relative to the range for a hammer) — NOT a win-rate or accuracy claim.
 * No pattern here is described with a historical win percentage; see the design note in
 * TradeSetupAnalyzer for why.
 */
public record CandlestickPattern(
        String type,           // HAMMER / BULLISH_ENGULFING / MORNING_STAR
        int startIndex,
        int endIndex,
        long time,
        double patternHigh,
        double patternLow,
        double strength,
        String description) {

    /** Base scoring points per the confirmation-score formula. */
    public int basePoints() {
        return switch (type) {
            case "MORNING_STAR" -> 2;
            case "BULLISH_ENGULFING" -> 1;
            case "HAMMER" -> 1;
            default -> 0;
        };
    }

    /** Overlap-resolution hierarchy: Morning Star > Bullish Engulfing > Hammer. */
    public int rank() {
        return switch (type) {
            case "MORNING_STAR" -> 3;
            case "BULLISH_ENGULFING" -> 2;
            case "HAMMER" -> 1;
            default -> 0;
        };
    }
}
