package com.javawarriors.breakout.intraday;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Thresholds for the two intraday strategies, bound from {@code intraday.*}.
 *
 * <p>The reversal numbers are the strategy as specified: above the 20 EMA, RSI over 65, and a
 * bearish reversal candle. The bullish side mirrors it with one deliberate difference - its RSI is
 * a <em>band</em> rather than a floor, so the scan cannot simply return whatever has already run
 * the furthest.
 */
@Component
@ConfigurationProperties(prefix = "intraday")
public class IntradayConfig {

    /** Candle size the scan runs on. Yahoo serves 5m/15m/60m for NSE. */
    private String interval = "15m";

    /** Fallback history window for an interval with no explicit entry in {@link #ranges}. */
    private String range = "5d";

    /**
     * History to pull per interval, because a fixed window does not survive a change of candle
     * size. Five sessions is 375 candles at 5m and 125 at 15m, but only ~35 at 60m - under the
     * {@link #minBars} floor, which silently skipped every symbol and returned an empty 60m scan.
     * The window has to grow as the candle does.
     */
    private Map<String, String> ranges = new LinkedHashMap<>(Map.of(
            "5m", "5d",
            "15m", "5d",
            "60m", "1mo"));

    private Set<String> allowedIntervals = Set.of("5m", "15m", "60m");

    /** History window for {@code interval}, falling back to {@link #getRange()}. */
    public String rangeFor(String interval) {
        return ranges.getOrDefault(interval, range);
    }

    // ---- Reversal (bearish, from a top) ----
    /** RSI above this is overbought enough for a rejection candle to mean something. */
    private double reversalRsiMin = 65;
    /** The EMA price must be above for the stock to count as extended rather than falling. */
    private int emaPeriod = 20;

    // ---- Bullish ----
    /** Strong, but capped: above this the move is already made and the entry is a chase. */
    private double bullishRsiMin = 55;
    private double bullishRsiMax = 70;
    /** Signal-candle volume as a multiple of the trailing average. */
    private double bullishVolumeRatio = 1.5;

    // ---- Shared ----
    private int rsiPeriod = 14;
    private int volumeLookback = 20;
    /** Minimum candles before a symbol can be scored at all. */
    private int minBars = 40;
    /** Rows returned per side. */
    private int maxResults = 100;

    public String getInterval() { return interval; }
    public void setInterval(String v) { this.interval = v; }
    public String getRange() { return range; }
    public void setRange(String v) { this.range = v; }
    public Map<String, String> getRanges() { return ranges; }
    public void setRanges(Map<String, String> v) { this.ranges = v; }
    public Set<String> getAllowedIntervals() { return allowedIntervals; }
    public void setAllowedIntervals(Set<String> v) { this.allowedIntervals = v; }
    public double getReversalRsiMin() { return reversalRsiMin; }
    public void setReversalRsiMin(double v) { this.reversalRsiMin = v; }
    public int getEmaPeriod() { return emaPeriod; }
    public void setEmaPeriod(int v) { this.emaPeriod = v; }
    public double getBullishRsiMin() { return bullishRsiMin; }
    public void setBullishRsiMin(double v) { this.bullishRsiMin = v; }
    public double getBullishRsiMax() { return bullishRsiMax; }
    public void setBullishRsiMax(double v) { this.bullishRsiMax = v; }
    public double getBullishVolumeRatio() { return bullishVolumeRatio; }
    public void setBullishVolumeRatio(double v) { this.bullishVolumeRatio = v; }
    public int getRsiPeriod() { return rsiPeriod; }
    public void setRsiPeriod(int v) { this.rsiPeriod = v; }
    public int getVolumeLookback() { return volumeLookback; }
    public void setVolumeLookback(int v) { this.volumeLookback = v; }
    public int getMinBars() { return minBars; }
    public void setMinBars(int v) { this.minBars = v; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int v) { this.maxResults = v; }
}
