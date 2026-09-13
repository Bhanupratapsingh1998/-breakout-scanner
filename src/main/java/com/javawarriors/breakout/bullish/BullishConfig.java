package com.javawarriors.breakout.bullish;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Every threshold the bullish ranking engine judges a stock against, in one place.
 *
 * <p>Bound from {@code bullish.*} in application.properties. Constructing one with {@code new}
 * yields the documented defaults, which is what the unit tests use — so a test never depends on
 * a Spring context, and a properties change can never silently alter what the tests assert.
 */
@Component
@ConfigurationProperties(prefix = "bullish")
public class BullishConfig {

    // ---- Classification bands (section 12 of the spec) ----
    private int scoreAPlus = 85;
    private int scoreStrong = 75;
    private int scoreWatchlist = 65;
    private int scoreNeutral = 50;

    /** A score floor for BUY NOW. Necessary but never sufficient — see PullbackAnalyzer. */
    private int buyNowMinScore = 70;

    // ---- Breakout / volume ----
    /** Breakout volume must beat the 20-day average by this multiple to count as confirmed. */
    private double breakoutVolumeRatio = 1.5;
    /** A close must clear resistance by this fraction before it is a breakout, not a poke. */
    private double breakoutConfirmBuffer = 0.005;
    /** Within this fraction *below* the level, a stock is "ready to break out" rather than idle. */
    private double nearBreakoutBand = 0.03;
    /** Within this fraction *above* the level, price is still in the retest zone. */
    private double retestBand = 0.03;

    // ---- Risk / reward ----
    private double minimumRiskReward = 2.0;
    private double preferredRiskReward = 3.0;
    /** Beyond this fraction above the ideal entry, buying at market is chasing. */
    private double maxEntryExtension = 0.05;
    /** A stop further than this fraction below entry is not a trade, it is a hope. */
    private double maxRiskPct = 0.10;
    /** Stops sit this many ATRs below the structural level, so an ordinary wick cannot trip them. */
    private double atrStopBuffer = 0.5;

    // ---- Overextension (section 9) ----
    private double extendedAtrsFromEma20 = 3.0;
    private double severeAtrsFromEma20 = 4.5;
    private double extendedRsi = 75;
    private double severeRsi = 82;
    /** Fraction above the breakout level past which the move is already made. */
    private double maxExtensionFromBreakout = 0.12;

    // ---- Pattern detection ----
    /**
     * Below this confidence a candidate is discarded rather than reported as a weak pattern.
     *
     * <p>Set above the detectors' base confidence of 55 on purpose. At 55 a candidate that merely
     * satisfies the structural tests would qualify, and a live Nifty 500 run produced a pattern for
     * 96% of stocks - which is exactly the "do not force a pattern onto every stock" failure the
     * spec warns against, and it also makes the 15-point component stop discriminating when nearly
     * everyone scores it. Requiring 65 means a pattern must also carry corroborating evidence
     * (volume dry-up, symmetry, a well-centred low) and not just the right shape.
     */
    private double minPatternConfidence = 65;

    // ---- Market regime (section 4) ----
    private int regimeBullishScore = 7;
    private int regimeBearishScore = 4;

    // ---- Universe / scan ----
    /** Minimum bars of history before a stock can be scored at all (EMA200 + convergence). */
    private int minBars = 250;
    /** Only the top N by score are returned by default; the whole ranking stays in memory. */
    private int maxResults = 250;

    public int getScoreAPlus() { return scoreAPlus; }
    public void setScoreAPlus(int v) { this.scoreAPlus = v; }
    public int getScoreStrong() { return scoreStrong; }
    public void setScoreStrong(int v) { this.scoreStrong = v; }
    public int getScoreWatchlist() { return scoreWatchlist; }
    public void setScoreWatchlist(int v) { this.scoreWatchlist = v; }
    public int getScoreNeutral() { return scoreNeutral; }
    public void setScoreNeutral(int v) { this.scoreNeutral = v; }
    public int getBuyNowMinScore() { return buyNowMinScore; }
    public void setBuyNowMinScore(int v) { this.buyNowMinScore = v; }
    public double getBreakoutVolumeRatio() { return breakoutVolumeRatio; }
    public void setBreakoutVolumeRatio(double v) { this.breakoutVolumeRatio = v; }
    public double getBreakoutConfirmBuffer() { return breakoutConfirmBuffer; }
    public void setBreakoutConfirmBuffer(double v) { this.breakoutConfirmBuffer = v; }
    public double getNearBreakoutBand() { return nearBreakoutBand; }
    public void setNearBreakoutBand(double v) { this.nearBreakoutBand = v; }
    public double getRetestBand() { return retestBand; }
    public void setRetestBand(double v) { this.retestBand = v; }
    public double getMinimumRiskReward() { return minimumRiskReward; }
    public void setMinimumRiskReward(double v) { this.minimumRiskReward = v; }
    public double getPreferredRiskReward() { return preferredRiskReward; }
    public void setPreferredRiskReward(double v) { this.preferredRiskReward = v; }
    public double getMaxEntryExtension() { return maxEntryExtension; }
    public void setMaxEntryExtension(double v) { this.maxEntryExtension = v; }
    public double getMaxRiskPct() { return maxRiskPct; }
    public void setMaxRiskPct(double v) { this.maxRiskPct = v; }
    public double getAtrStopBuffer() { return atrStopBuffer; }
    public void setAtrStopBuffer(double v) { this.atrStopBuffer = v; }
    public double getExtendedAtrsFromEma20() { return extendedAtrsFromEma20; }
    public void setExtendedAtrsFromEma20(double v) { this.extendedAtrsFromEma20 = v; }
    public double getSevereAtrsFromEma20() { return severeAtrsFromEma20; }
    public void setSevereAtrsFromEma20(double v) { this.severeAtrsFromEma20 = v; }
    public double getExtendedRsi() { return extendedRsi; }
    public void setExtendedRsi(double v) { this.extendedRsi = v; }
    public double getSevereRsi() { return severeRsi; }
    public void setSevereRsi(double v) { this.severeRsi = v; }
    public double getMaxExtensionFromBreakout() { return maxExtensionFromBreakout; }
    public void setMaxExtensionFromBreakout(double v) { this.maxExtensionFromBreakout = v; }
    public double getMinPatternConfidence() { return minPatternConfidence; }
    public void setMinPatternConfidence(double v) { this.minPatternConfidence = v; }
    public int getRegimeBullishScore() { return regimeBullishScore; }
    public void setRegimeBullishScore(int v) { this.regimeBullishScore = v; }
    public int getRegimeBearishScore() { return regimeBearishScore; }
    public void setRegimeBearishScore(int v) { this.regimeBearishScore = v; }
    public int getMinBars() { return minBars; }
    public void setMinBars(int v) { this.minBars = v; }
    public int getMaxResults() { return maxResults; }
    public void setMaxResults(int v) { this.maxResults = v; }
}
