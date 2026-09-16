package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.index500.pattern.PatternResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything this feature knows about one stock, and the single definition of its JSON shape.
 *
 * <p>A response DTO, deliberately not an entity: nothing here is persisted, and tying the API to a
 * database row would mean a schema change every time a column is added to the table.
 *
 * <p>A stock whose data could not be read is still represented, with {@code status}
 * {@link #DATA_UNAVAILABLE} and a reason. Dropping it would make the universe silently shrink and
 * leave no way to tell a stock that is fine from one that was never checked.
 */
public record Index500Analysis(
        StockMetadata metadata,
        boolean analysed, String status, String statusReason, String unavailableReason,
        double price,
        double return1dPct, double return1mPct, double return3mPct, double return6mPct, double return1yPct,
        double drop6mPct, double fromHigh52wPct, double fromLow52wPct,
        double high52w, double low52w,
        double ema20, double ema50, double ema200, String emaStatus,
        double rsi, double adx, double atr, double volume, double avgVolume20, double volumeRatio,
        double support, double resistance,
        List<PatternResult> patterns, PatternResult bestPattern,
        OpportunityScore score) {

    // Statuses, strongest evidence first. "BUY" is deliberately absent - a stock being down 40% is
    // not a reason to buy it, and the spec is explicit that this screen must not imply otherwise.
    public static final String STRONG_REVERSAL = "STRONG REVERSAL";
    public static final String REVERSAL_WATCH = "REVERSAL WATCH";
    public static final String BREAKOUT_CONFIRMED = "BREAKOUT CONFIRMED";
    public static final String BREAKOUT_CANDIDATE = "BREAKOUT CANDIDATE";
    public static final String RECOVERY = "RECOVERY";
    public static final String WAIT_FOR_CONFIRMATION = "WAIT FOR CONFIRMATION";
    public static final String WEAK = "WEAK";
    public static final String AVOID = "AVOID";
    public static final String DATA_UNAVAILABLE = "DATA_UNAVAILABLE";

    /** The placeholder used when a stock's data could not be read. */
    public static Index500Analysis unavailable(StockMetadata metadata, String reason) {
        return new Index500Analysis(metadata, false, DATA_UNAVAILABLE,
                "No usable price history for this symbol, so it was not analysed.", reason,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, Double.NaN, "UNKNOWN",
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                Double.NaN, Double.NaN, List.of(), null, OpportunityScore.zero());
    }

    public String symbol() {
        return metadata.symbol();
    }

    public String sector() {
        return metadata.sector();
    }

    public boolean hasPattern(String type) {
        if (type == null || type.isBlank() || "ALL".equalsIgnoreCase(type)) return true;
        return patterns.stream().anyMatch(p -> p.patternType().equalsIgnoreCase(type));
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.putAll(metadata.toRow());
        row.put("analysed", analysed);
        row.put("status", status);
        row.put("statusReason", statusReason);
        if (unavailableReason != null) row.put("unavailableReason", unavailableReason);
        if (!analysed) return row;

        row.put("price", price);
        row.put("return1dPct", return1dPct);
        row.put("return1mPct", return1mPct);
        row.put("return3mPct", return3mPct);
        row.put("return6mPct", return6mPct);
        row.put("return1yPct", return1yPct);
        row.put("drop6mPct", drop6mPct);
        row.put("fromHigh52wPct", fromHigh52wPct);
        row.put("fromLow52wPct", fromLow52wPct);
        row.put("high52w", high52w);
        row.put("low52w", low52w);
        row.put("ema20", ema20);
        row.put("ema50", ema50);
        row.put("ema200", ema200);
        row.put("emaStatus", emaStatus);
        row.put("rsi", rsi);
        row.put("adx", adx);
        row.put("atr", atr);
        row.put("volume", volume);
        row.put("avgVolume20", avgVolume20);
        row.put("volumeRatio", volumeRatio);
        if (!Double.isNaN(support)) row.put("support", support);
        if (!Double.isNaN(resistance)) row.put("resistance", resistance);

        List<Map<String, Object>> patternRows = new ArrayList<>();
        for (PatternResult p : patterns) patternRows.add(p.toRow());
        row.put("patterns", patternRows);
        row.put("patternCount", patterns.size());
        row.put("bestPattern", bestPattern == null ? null : bestPattern.toRow());
        row.put("patternName", bestPattern == null ? "No clear pattern" : bestPattern.patternName());
        row.put("score", score.total());
        row.put("scoreBreakdown", score.toRow());
        return row;
    }
}
