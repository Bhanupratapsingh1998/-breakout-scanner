package com.javawarriors.breakout.wick;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One wick-reversal signal, and the single definition of its JSON shape.
 *
 * <p>Every number the score was built from is carried alongside the score itself. A row that says
 * "72" and nothing else cannot be checked; a row that shows the wick was 4.1x its body and the
 * close landed 88% of the range away from the extreme it rejected can be disagreed with, which is
 * the point.
 *
 * <p>{@code direction} says which way the rejection points, and several fields are read relative to
 * it: {@code wickToBody} measures the lower wick for a bullish signal and the upper one for a
 * bearish one, {@code closePosition} measures distance from the rejected extreme, and
 * {@code triggerLevel} and {@code invalidationLevel} swap ends.
 */
public record WickSignal(
        String symbol, String companyName, String sector,
        String interval, int candles, String mergedInterval, String direction,
        long signalTime, int barsAgo,
        double firstOpen, double firstHigh, double firstLow, double firstClose,
        double secondOpen, double secondHigh, double secondLow, double secondClose,
        double mergedOpen, double mergedHigh, double mergedLow, double mergedClose,
        double bodySize, double lowerWick, double upperWick, double wickToBody,
        double closePosition, double recoveredPct,
        double priorMoveInRanges, double distanceFromExtremeRanges, double volumeRatio,
        double price, double triggerLevel, double invalidationLevel, double riskPct,
        String status, String statusReason,
        double score, Map<String, Double> scoreParts) {

    /** Sellers pushed price down and lost it back - a long lower wick. */
    public static final String BULLISH = "BULLISH";
    /** Buyers pushed price up and lost it back - a long upper wick. */
    public static final String BEARISH = "BEARISH";

    /** Price has since closed through the far side - the rejection was acted on. */
    public static final String CONFIRMED = "CONFIRMED";
    /** Price has since closed below the wick low - the rejection failed. */
    public static final String INVALIDATED = "INVALIDATED";
    /** Neither has happened yet; the level is still live. */
    public static final String PENDING = "PENDING";

    /** The component labels, in the order the score adds them up. */
    public static final List<String> SCORE_KEYS =
            List.of("wick", "recovery", "priorMove", "atExtreme", "volume");

    public boolean bullish() {
        return BULLISH.equals(direction);
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("companyName", companyName);
        row.put("sector", sector);
        row.put("interval", interval);
        row.put("candles", candles);
        row.put("mergedInterval", mergedInterval);
        row.put("direction", direction);
        row.put("signalTime", signalTime);
        row.put("barsAgo", barsAgo);

        // The first and last candles' own extremes, not the merged ones: the UI draws them beside
        // the merged candle, and reusing its high and low would show each part spanning the whole
        // range. A three-candle group's middle candle is not carried - the drawing shows the ends.
        Map<String, Object> pair = new LinkedHashMap<>();
        pair.put("firstOpen", firstOpen);
        pair.put("firstHigh", firstHigh);
        pair.put("firstLow", firstLow);
        pair.put("firstClose", firstClose);
        pair.put("secondOpen", secondOpen);
        pair.put("secondHigh", secondHigh);
        pair.put("secondLow", secondLow);
        pair.put("secondClose", secondClose);
        row.put("pair", pair);

        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("open", mergedOpen);
        merged.put("high", mergedHigh);
        merged.put("low", mergedLow);
        merged.put("close", mergedClose);
        merged.put("body", bodySize);
        merged.put("lowerWick", lowerWick);
        merged.put("upperWick", upperWick);
        row.put("merged", merged);

        row.put("wickToBody", wickToBody);
        row.put("closePosition", closePosition);
        row.put("recoveredPct", recoveredPct);
        row.put("priorMoveInRanges", priorMoveInRanges);
        row.put("distanceFromExtremeRanges", distanceFromExtremeRanges);
        row.put("volumeRatio", volumeRatio);

        row.put("price", price);
        // Which price is which flips with direction: a bullish rejection is proved right by a close
        // above the candle and wrong by one below it, and a bearish one the other way round.
        row.put("triggerLevel", triggerLevel);
        row.put("invalidationLevel", invalidationLevel);
        row.put("riskPct", riskPct);

        row.put("status", status);
        row.put("statusReason", statusReason);
        row.put("score", score);
        row.put("scoreParts", scoreParts);
        return row;
    }
}
