package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every threshold the wick-reversal scan uses, so none of them is buried in a method. */
@Component
@ConfigurationProperties(prefix = "wick")
public class WickReversalConfig {

    /** Candle sizes offered. Adjacent candles of the chosen size are merged into one bigger one. */
    private List<String> intervals = List.of("15m", "60m", "1d");

    /** The one used when a request does not name an interval. Daily shares the other scans' cache. */
    private String defaultInterval = "1d";

    /**
     * How many adjacent candles to merge. Every listed size is detected in one pass over the same
     * bars, so the UI filter between them costs nothing - the fetch, not the arithmetic, is what a
     * scan spends its time on.
     */
    private List<Integer> candleCounts = List.of(2, 3);

    /**
     * Which way round to look. Bullish is the setup as drawn - a drop bought back into a long lower
     * wick; bearish is its mirror, a rally sold back into a long upper wick. Both share one code
     * path and one pass over the bars, so enabling both costs nothing but the arithmetic.
     */
    private List<String> directions = List.of("BULLISH", "BEARISH");

    /**
     * History per candle size, because a fixed window does not survive a change of candle size.
     *
     * <p>The daily entry is deliberately the same 18mo window the bullish ranking and the Index 500
     * analysis use: identical key, so a daily wick scan after either of those is served entirely
     * from {@code BarCache} and fetches nothing.
     */
    private Map<String, String> ranges = new LinkedHashMap<>(Map.of(
            "15m", "1mo",
            "60m", "3mo",
            "1d", "18mo"));

    /** Fallback window for an interval with no explicit entry above. */
    private String range = "1mo";

    /** Minimum base candles before a symbol can be judged at all. */
    private int minBars = 40;

    /** How many of the most recent merged candles to search. Older signals are stale, not wrong. */
    private int lookbackBars = 20;

    /** Base candles of context used to measure the decline the group rejected. */
    private int contextBars = 10;

    /** Base candles the group's low is compared against to see whether it marks a swing low. */
    private int swingLookback = 20;

    /** The lower wick must be at least this many times the merged body to count as a rejection. */
    private double minWickToBody = 2.0;

    /**
     * The merged candle must close in at least this much of its own range.
     *
     * <p>This is the "was the drop actually bought back" test, and it is what separates the setup in
     * the reference images from a plain long-legged candle: the last candle has to finish the job.
     */
    private double minClosePosition = 0.5;

    /** Rows returned by one scan. */
    private int maxResults = 100;

    /** Only signals scoring at least this are kept, so the list is short enough to act on. */
    private double minScore = 40;

    /** History window for {@code interval}, falling back to {@link #getRange()}. */
    public String rangeFor(String interval) {
        return ranges.getOrDefault(interval, range);
    }

    /**
     * What {@code count} candles of {@code interval} add up to, for display: two 15m candles are
     * one 30m, three are one 45m.
     *
     * <p>Computed rather than looked up, so adding an interval or a group size cannot leave a row
     * labelled "3 × 15m" while its neighbour says "45m".
     */
    public static String mergedLabel(String interval, int count) {
        if (interval == null) return count + " candles";
        if (interval.endsWith("d")) {
            int days = parseLeading(interval, 1) * count;
            return days + "-day";
        }
        int minutes = minutesOf(interval);
        if (minutes <= 0) return count + " × " + interval;
        int total = minutes * count;
        if (total < 60) return total + "m";
        return total % 60 == 0 ? (total / 60) + "h" : (total / 60) + "h" + (total % 60) + "m";
    }

    private static int minutesOf(String interval) {
        if (interval.endsWith("h")) return parseLeading(interval, 0) * 60;
        if (interval.endsWith("m")) return parseLeading(interval, 0);
        return 0;
    }

    private static int parseLeading(String interval, int fallback) {
        String digits = interval.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? fallback : Integer.parseInt(digits);
    }

    /** The configured directions as the shared enum, ignoring anything unrecognised. */
    public List<Direction> enabledDirections() {
        List<Direction> out = new ArrayList<>();
        for (String d : directions) {
            if ("BULLISH".equalsIgnoreCase(d)) out.add(Direction.BULLISH);
            else if ("BEARISH".equalsIgnoreCase(d)) out.add(Direction.BEARISH);
        }
        return out.isEmpty() ? List.of(Direction.BULLISH) : out;
    }

    public boolean isKnownDirection(String direction) {
        if (direction == null || direction.isBlank() || "ALL".equalsIgnoreCase(direction)) return true;
        return directions.stream().anyMatch(d -> d.equalsIgnoreCase(direction));
    }

    public boolean isKnownCount(Integer count) {
        return count == null || candleCounts.contains(count);
    }

    public boolean isKnownInterval(String interval) {
        return interval == null || interval.isBlank() || intervals.contains(interval);
    }

    public List<String> getDirections() { return directions; }
    public void setDirections(List<String> v) { this.directions = v; }
    public List<Integer> getCandleCounts() { return candleCounts; }
    public void setCandleCounts(List<Integer> v) { this.candleCounts = v; }
    public List<String> getIntervals() { return intervals; }
    public void setIntervals(List<String> v) { this.intervals = v; }
    public String getDefaultInterval() { return defaultInterval; }
    public void setDefaultInterval(String v) { this.defaultInterval = v; }
    public Map<String, String> getRanges() { return ranges; }
    public void setRanges(Map<String, String> v) { this.ranges = v; }
    public String getRange() { return range; }
    public void setRange(String v) { this.range = v; }
    public int getMinBars() { return minBars; }
    public void setMinBars(int v) { this.minBars = v; }
    public int getLookbackBars() { return lookbackBars; }
    public void setLookbackBars(int v) { this.lookbackBars = v; }
    public int getContextBars() { return contextBars; }
    public void setContextBars(int v) { this.contextBars = v; }
    public int getSwingLookback() { return swingLookback; }
    public void setSwingLookback(int v) { this.swingLookback = v; }
    public double getMinWickToBody() { return minWickToBody; }
    public void setMinWickToBody(double v) { this.minWickToBody = v; }
    public double getMinClosePosition() { return minClosePosition; }
    public void setMinClosePosition(double v) { this.minClosePosition = v; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int v) { this.maxResults = v; }
    public double getMinScore() { return minScore; }
    public void setMinScore(double v) { this.minScore = v; }
}
