package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry, stop, two targets and the resulting risk/reward - worth 10 points.
 *
 * <p>Section 10 ends with "do not force a trade plan if there is no logical stop or target", and
 * that is the rule this class is organised around. There are three ways to get {@link #none()}
 * back rather than a plan: no structural level exists to put a stop under, the nearest such level
 * is further than {@code bullish.max-risk-pct} below entry, or no target above entry can be
 * justified from anything other than wishful arithmetic. A stock with no plan still gets ranked -
 * it just cannot be called an entry, and it forfeits the 10 risk/reward points.
 *
 * <p>The stop is placed under structure and then buffered by half an ATR, never at a round
 * percentage. A stop sitting exactly on the level everyone can see is the one price is most likely
 * to touch before continuing; the ATR buffer is what keeps ordinary noise from booking a loss on a
 * setup that was right.
 *
 * <p>Entry depends on stage rather than always being the last close: a stock that has not broken
 * out yet is entered just above its level, and one that is retesting is entered at the level, so
 * the plan describes the trade the user should actually place rather than pricing everything at
 * market.
 */
public final class TradePlanCalculator {

    public static final double MAX_POINTS = 10;

    public record TradePlan(boolean present, double entry, double stopLoss, double target1,
                            double target2, double riskPct, double rewardPct, double riskReward,
                            String entryType, String stopBasis, String targetBasis, double points,
                            String explanation) {

        public static TradePlan none(String why) {
            return new TradePlan(false, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, Double.NaN, "NONE", "NONE", "NONE", 0, why);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("present", present);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("explanation", explanation);
            if (present) {
                row.put("entry", entry);
                row.put("stopLoss", stopLoss);
                row.put("target1", target1);
                row.put("target2", target2);
                row.put("riskPct", riskPct);
                row.put("rewardPct", rewardPct);
                row.put("riskReward", riskReward);
                row.put("entryType", entryType);
                row.put("stopBasis", stopBasis);
                row.put("targetBasis", targetBasis);
            }
            return row;
        }
    }

    private TradePlanCalculator() {
    }

    public static TradePlan calculate(IndicatorSnapshot s, BreakoutStage breakout, ChartPattern pattern,
                                      Overextension over, BullishConfig cfg) {
        double atr = s.lastAtr;
        if (!(atr > 0)) return TradePlan.none("No ATR available - too little history to size risk.");

        // ---- Entry, by stage.
        double entry;
        String entryType;
        if (!breakout.confirmed() && breakout.hasLevel()) {
            entry = breakout.confirmedLevel();
            entryType = "ON BREAKOUT";
        } else if (breakout.confirmed() && breakout.hasLevel()
                && breakout.distancePct() <= cfg.getRetestBand() * 100) {
            entry = Math.min(s.price, breakout.level());
            entryType = "ON RETEST";
        } else {
            entry = s.price;
            entryType = "AT MARKET";
        }
        if (!(entry > 0)) return TradePlan.none("No usable entry price.");

        // ---- Stop: the highest structural level that is still below entry, buffered by ATR.
        // Taking the highest keeps risk as small as the structure honestly allows; taking any level
        // below entry keeps it a real level rather than a percentage.
        double swingLow = s.lastSwingLow();
        double patternInvalidation = pattern.isPresent() ? pattern.invalidationLevel() : Double.NaN;
        double breakoutLevel = breakout.hasLevel() && breakout.confirmed() ? breakout.level() : Double.NaN;

        double structure = Double.NaN;
        String stopBasis = "NONE";
        if (valid(swingLow) && swingLow < entry) {
            structure = swingLow;
            stopBasis = "SWING LOW";
        }
        if (valid(patternInvalidation) && patternInvalidation < entry
                && (Double.isNaN(structure) || patternInvalidation > structure)) {
            structure = patternInvalidation;
            stopBasis = "PATTERN INVALIDATION";
        }
        if (valid(breakoutLevel) && breakoutLevel < entry
                && (Double.isNaN(structure) || breakoutLevel > structure)) {
            structure = breakoutLevel;
            stopBasis = "BREAKOUT LEVEL";
        }
        if (Double.isNaN(structure)) {
            return TradePlan.none("No swing low, pattern invalidation or cleared level sits below"
                    + " the entry, so there is nowhere logical to place a stop.");
        }

        double stop = structure - cfg.getAtrStopBuffer() * atr;
        double risk = entry - stop;
        if (!(risk > 0)) return TradePlan.none("The structural stop is not below the entry.");

        double riskPct = risk / entry * 100;
        if (riskPct > cfg.getMaxRiskPct() * 100) {
            return TradePlan.none(String.format("The nearest logical stop is %.1f%% away, beyond the"
                    + " %.0f%% maximum - the structure is too loose to trade from here.",
                    riskPct, cfg.getMaxRiskPct() * 100));
        }

        // ---- Targets. T1 is the nearest genuine obstacle, T2 the pattern's measured move.
        double nearestResistance = s.nearestResistanceAbove(entry);
        double measuredMove = pattern.isPresent() ? pattern.target() : Double.NaN;

        double target1 = Double.NaN;
        String targetBasis = "NONE";
        if (valid(nearestResistance) && nearestResistance > entry + risk) {
            // Only useful as a target if it is at least 1R away; closer than that and the trade is
            // paying full risk to reach the first obstacle.
            target1 = nearestResistance;
            targetBasis = "NEAREST RESISTANCE";
        }
        if (Double.isNaN(target1) && valid(measuredMove) && measuredMove > entry) {
            target1 = measuredMove;
            targetBasis = "PATTERN MEASURED MOVE";
        }
        if (Double.isNaN(target1)) {
            // No obstacle overhead at all means blue sky. That is a real, common situation on a
            // 52-week breakout, and an ATR projection is the honest way to price it.
            target1 = entry + 3 * atr;
            targetBasis = "ATR PROJECTION (no overhead resistance)";
        }

        double target2 = Double.NaN;
        if (valid(measuredMove) && measuredMove > target1) target2 = measuredMove;
        else target2 = Math.max(target1 + 2 * atr, entry + 2 * (target1 - entry));

        double reward = target1 - entry;
        if (!(reward > 0)) return TradePlan.none("No target above the entry could be justified.");

        double riskReward = reward / risk;
        double rewardPct = reward / entry * 100;

        double points = score(riskReward, riskPct, entry, s.price, over, cfg);

        String explanation = String.format(
                "Enter %s at %.2f, stop %.2f (%s, half an ATR under it) risking %.1f%%, first target"
                        + " %.2f (%s) for %.1f%% - %.1f:1.",
                entryType.toLowerCase(), entry, stop, stopBasis.toLowerCase(), riskPct, target1,
                targetBasis.toLowerCase(), rewardPct, riskReward);

        return new TradePlan(true, entry, stop, target1, target2, riskPct, rewardPct, riskReward,
                entryType, stopBasis, targetBasis, points, explanation);
    }

    /**
     * Risk/reward points. The ratio carries 6, the absolute size of the risk 2, and how far the
     * current price sits above the planned entry the last 2 - a 4:1 plan whose entry is 8% below
     * where the stock is trading is not a 4:1 plan any more.
     */
    private static double score(double riskReward, double riskPct, double entry, double price,
                                Overextension over, BullishConfig cfg) {
        double ratioPoints;
        if (riskReward >= cfg.getPreferredRiskReward()) ratioPoints = 6;
        else if (riskReward >= cfg.getMinimumRiskReward()) ratioPoints = 4.5;
        else if (riskReward >= 1.5) ratioPoints = 2;
        else ratioPoints = 0;

        double riskPoints;
        if (riskPct <= 4) riskPoints = 2;
        else if (riskPct <= 7) riskPoints = 1.2;
        else riskPoints = 0.5;

        double extensionFromEntry = entry <= 0 ? Double.NaN : (price - entry) / entry;
        double entryPoints;
        if (Double.isNaN(extensionFromEntry)) entryPoints = 0;
        else if (extensionFromEntry <= 0) entryPoints = 2;          // price is at or below the entry
        else if (extensionFromEntry <= cfg.getMaxEntryExtension()) entryPoints = 1.5;
        else if (extensionFromEntry <= cfg.getMaxEntryExtension() * 2) entryPoints = 0.5;
        else entryPoints = 0;

        // An overextended stock cannot earn full entry-quality credit whatever the arithmetic says.
        if (over.isSevere()) entryPoints = 0;
        else if (over.isModerate()) entryPoints = Math.min(entryPoints, 0.5);

        return ratioPoints + riskPoints + entryPoints;
    }

    private static boolean valid(double v) {
        return !Double.isNaN(v) && !Double.isInfinite(v) && v > 0;
    }
}
