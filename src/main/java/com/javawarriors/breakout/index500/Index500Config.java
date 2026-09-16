package com.javawarriors.breakout.index500;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Thresholds for the Index 500 analysis, bound from {@code index500.*}.
 *
 * <p>Constructing one with {@code new} yields the documented defaults, which is what the unit tests
 * assert against, so a properties change can never silently rewrite what the tests mean.
 */
@Component
@ConfigurationProperties(prefix = "index500")
public class Index500Config {

    /** Daily history to pull. Shared with the other scans so they reuse each other's cached bars. */
    private String range = "18mo";

    /** Minimum bars before a stock can be analysed at all - EMA200 plus room to converge. */
    private int minBars = 250;

    /** A 6-month fall of at least this much is what "declined" means in the summary counts. */
    private double declineThresholdPct = 10;

    /** RSI at or below this counts as still oversold when classifying status. */
    private double oversoldRsi = 40;

    /** Volume multiple that counts as confirmation on a reversal candle. */
    private double confirmingVolumeRatio = 1.2;

    /** Pattern confidence at or above this is treated as a high-quality signal. */
    private double strongPatternConfidence = 7.0;

    /** Rows returned by one analysis request. */
    private int maxResults = 250;

    public String getRange() { return range; }
    public void setRange(String v) { this.range = v; }
    public int getMinBars() { return minBars; }
    public void setMinBars(int v) { this.minBars = v; }
    public double getDeclineThresholdPct() { return declineThresholdPct; }
    public void setDeclineThresholdPct(double v) { this.declineThresholdPct = v; }
    public double getOversoldRsi() { return oversoldRsi; }
    public void setOversoldRsi(double v) { this.oversoldRsi = v; }
    public double getConfirmingVolumeRatio() { return confirmingVolumeRatio; }
    public void setConfirmingVolumeRatio(double v) { this.confirmingVolumeRatio = v; }
    public double getStrongPatternConfidence() { return strongPatternConfidence; }
    public void setStrongPatternConfidence(double v) { this.strongPatternConfidence = v; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int v) { this.maxResults = v; }
}
