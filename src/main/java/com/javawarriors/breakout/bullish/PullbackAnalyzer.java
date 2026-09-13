package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where in its own cycle this setup currently sits, and therefore what a user should do about it.
 *
 * <p>Two outputs, and the split matters. The <b>setup stage</b> is an observation - the breakout
 * held, or price is retesting it, or the base has not broken yet. The <b>trade status</b> is a
 * recommendation, and it is produced by a decision tree in which the score is the very last input
 * consulted. Section 12 is explicit that a high score alone must never produce BUY NOW, so the
 * tree checks, in order: has the breakout failed, is price overextended, has anything broken at
 * all, is price in the retest zone - and only a setup that survives all of those is even eligible
 * for BUY NOW, which then additionally requires a valid trade plan, a market that is not bearish,
 * and a score above the configured floor.
 *
 * <p>The asymmetry is intentional. Every path out of this tree except one is a form of "wait",
 * because waiting costs nothing and chasing costs the difference between the entry and the stop.
 */
public final class PullbackAnalyzer {

    // Setup stages (observations)
    public static final String BREAKOUT_CONFIRMED = "BREAKOUT CONFIRMED";
    public static final String BREAKOUT_RETEST = "BREAKOUT RETEST";
    public static final String PULLBACK_OPPORTUNITY = "PULLBACK OPPORTUNITY";
    public static final String READY_TO_BREAKOUT = "READY TO BREAKOUT";
    public static final String EXTENDED = "EXTENDED";
    public static final String FAILED_BREAKOUT = "FAILED BREAKOUT";
    public static final String BASE_BUILDING = "BASE BUILDING";

    // Trade statuses (recommendations)
    public static final String BUY_NOW = "BUY NOW";
    public static final String WAIT_FOR_BREAKOUT = "WAIT FOR BREAKOUT";
    public static final String WAIT_FOR_RETEST = "WAIT FOR RETEST";
    public static final String WAIT_FOR_PULLBACK = "WAIT FOR PULLBACK";
    public static final String AVOID_CHASING = "AVOID CHASING";
    public static final String FAILED_SETUP = "FAILED SETUP";

    public record SetupStage(String stage, String tradeStatus, boolean tradeable, String reason) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", stage);
            row.put("tradeStatus", tradeStatus);
            row.put("tradeable", tradeable);
            row.put("reason", reason);
            return row;
        }
    }

    private PullbackAnalyzer() {
    }

    /** The observable stage, with no recommendation attached. */
    public static String stageOf(IndicatorSnapshot s, BreakoutStage breakout, Overextension over,
                                 BullishConfig cfg) {
        if (breakout.failed()) return FAILED_BREAKOUT;
        if (over.isSevere()) return EXTENDED;

        if (breakout.confirmed()) {
            double retestBandPct = cfg.getRetestBand() * 100;
            // Back inside the retest band after having broken out, with the level now underneath:
            // previous resistance is being tested as support.
            if (breakout.distancePct() <= retestBandPct && breakout.barsSinceBreakout() >= 1) {
                return BREAKOUT_RETEST;
            }
            if (over.isModerate()) return PULLBACK_OPPORTUNITY;
            return BREAKOUT_CONFIRMED;
        }

        if (breakout.nearBreakout()) return READY_TO_BREAKOUT;
        return BASE_BUILDING;
    }

    /**
     * The recommendation. {@code score} and {@code riskReward} are consulted only after every
     * structural check has passed - see the class javadoc.
     *
     * @param riskReward NaN when no valid trade plan could be built
     * @param trendIssue null when the trend supports an entry; otherwise what is wrong with it
     */
    public static SetupStage classify(IndicatorSnapshot s, BreakoutStage breakout, Overextension over,
                                      MarketRegimeAnalyzer.Regime regime, double score,
                                      double riskReward, String trendIssue, BullishConfig cfg) {
        String stage = stageOf(s, breakout, over, cfg);

        if (FAILED_BREAKOUT.equals(stage)) {
            return new SetupStage(stage, FAILED_SETUP, false,
                    "Price broke out and then closed back below the level it cleared.");
        }
        if (over.isSevere()) {
            return new SetupStage(stage, AVOID_CHASING, false, over.explanation());
        }
        if (over.isModerate()) {
            return new SetupStage(stage, WAIT_FOR_PULLBACK, false, over.explanation());
        }
        if (trendIssue != null) {
            // The caller names which condition failed. It used to be a bare boolean reported as
            // "price is not above all three moving averages", which was simply false for the most
            // common case: a stock perfectly stacked on the daily chart sitting inside a falling
            // weekly trend. An explanation that contradicts the one printed beside it is worse
            // than no explanation.
            return new SetupStage(stage, WAIT_FOR_BREAKOUT, false, "The trend is not yet aligned - " + trendIssue + ".");
        }
        if (!breakout.confirmed()) {
            return new SetupStage(stage, WAIT_FOR_BREAKOUT, false,
                    breakout.nearBreakout()
                            ? String.format("Coiled %.1f%% under its breakout level - the level has"
                                    + " not been cleared on a close yet.", -breakout.distancePct())
                            : "No level has been cleared; the base is still forming.");
        }

        boolean hasPlan = !Double.isNaN(riskReward) && riskReward >= cfg.getMinimumRiskReward();
        boolean scoreQualifies = score >= cfg.getBuyNowMinScore();

        if (BREAKOUT_RETEST.equals(stage)) {
            // A retest that is holding is the best entry this engine produces, so it can be BUY NOW
            // — but only on the same terms as any other entry.
            if (hasPlan && scoreQualifies && regime.allowsBuyNow()) {
                return new SetupStage(stage, BUY_NOW, true,
                        "Previous resistance is being retested as support with the trend intact and"
                                + " risk/reward still favourable.");
            }
            return new SetupStage(stage, WAIT_FOR_RETEST, false,
                    blockedReason(hasPlan, scoreQualifies, regime, riskReward, score, cfg));
        }

        if (hasPlan && scoreQualifies && regime.allowsBuyNow()) {
            return new SetupStage(stage, BUY_NOW, true,
                    "Breakout confirmed, price is not extended, and the trade plan clears the"
                            + " minimum risk/reward.");
        }
        return new SetupStage(stage, WAIT_FOR_RETEST, false,
                blockedReason(hasPlan, scoreQualifies, regime, riskReward, score, cfg));
    }

    private static String blockedReason(boolean hasPlan, boolean scoreQualifies,
                                        MarketRegimeAnalyzer.Regime regime, double riskReward,
                                        double score, BullishConfig cfg) {
        if (!regime.allowsBuyNow()) {
            return "The setup is intact, but the broad market is bearish - no entries are called in"
                    + " this regime.";
        }
        if (!hasPlan) {
            return Double.isNaN(riskReward)
                    ? "No logical stop and target could be placed, so no trade plan is offered."
                    : String.format("Risk/reward is only %.1f:1, below the %.1f:1 minimum.",
                            riskReward, cfg.getMinimumRiskReward());
        }
        if (!scoreQualifies) {
            return String.format("Score %.0f is below the %d needed before an entry is called.",
                    score, cfg.getBuyNowMinScore());
        }
        return "Waiting for a better entry.";
    }
}
