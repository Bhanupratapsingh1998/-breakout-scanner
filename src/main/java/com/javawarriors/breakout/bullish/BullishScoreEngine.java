package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishTrendAnalyzer.Trend;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.MomentumAnalyzer.Momentum;
import com.javawarriors.breakout.bullish.RelativeStrengthAnalyzer.RelativeStrength;
import com.javawarriors.breakout.bullish.TradePlanCalculator.TradePlan;
import com.javawarriors.breakout.bullish.VolumeAnalyzer.VolumeProfile;
import com.javawarriors.breakout.timeframe.TrendAlignment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Adds the eight components up to 100 and turns the total into a classification.
 *
 * <p>Weights are exactly the spec's: Trend 20, Relative Strength 15, Pattern Quality 15, Momentum
 * 10, Volume 10, Price Structure 10, Breakout Quality 10, Risk/Reward 10. Each component is
 * computed by its own analyzer and arrives here already scored, so this class does no analysis of
 * its own beyond the price-structure component and the market-regime adjustment - it exists to
 * make the arithmetic visible in one place, and every component is carried through to the API so
 * the dashboard can show why a stock scored what it did.
 *
 * <p>The regime multiplier is applied last, to the total. Applying it per component would let a
 * bearish market quietly change the shape of the breakdown the user is reading.
 */
public final class BullishScoreEngine {

    public static final double MAX_SCORE = 100;
    public static final double PRICE_STRUCTURE_MAX = 10;

    public record BullishScore(double trend, double relativeStrength, double momentum, double volume,
                               double priceStructure, double patternQuality, double breakoutQuality,
                               double riskReward, double rawTotal, double regimeMultiplier,
                               double total, String classification) {

        public Map<String, Object> toRow() {
            Map<String, Object> components = new LinkedHashMap<>();
            components.put("trend", component(trend, BullishTrendAnalyzer.MAX_POINTS));
            components.put("relativeStrength", component(relativeStrength, RelativeStrengthAnalyzer.MAX_POINTS));
            components.put("momentum", component(momentum, MomentumAnalyzer.MAX_POINTS));
            components.put("volume", component(volume, VolumeAnalyzer.MAX_POINTS));
            components.put("priceStructure", component(priceStructure, PRICE_STRUCTURE_MAX));
            components.put("patternQuality", component(patternQuality, ChartPatternDetector.MAX_POINTS));
            components.put("breakoutQuality", component(breakoutQuality, BreakoutStageAnalyzer.MAX_POINTS));
            components.put("riskReward", component(riskReward, TradePlanCalculator.MAX_POINTS));

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("total", total);
            row.put("rawTotal", rawTotal);
            row.put("maxScore", MAX_SCORE);
            row.put("regimeMultiplier", regimeMultiplier);
            row.put("classification", classification);
            row.put("components", components);
            return row;
        }

        private static Map<String, Object> component(double points, double max) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("points", round1(points));
            m.put("maxPoints", max);
            return m;
        }
    }

    private BullishScoreEngine() {
    }

    public static BullishScore score(IndicatorSnapshot s, Trend trend, RelativeStrength rs,
                                     Momentum momentum, VolumeProfile volume, ChartPattern pattern,
                                     BreakoutStage breakout, TradePlan plan,
                                     TrendAlignment.Alignment higherTimeframes,
                                     MarketRegimeAnalyzer.Regime regime, BullishConfig cfg) {
        double structure = priceStructurePoints(s, trend, higherTimeframes);

        double raw = trend.points() + rs.points() + momentum.points() + volume.points()
                + structure + pattern.points() + breakout.points() + plan.points();
        raw = clamp(raw, 0, MAX_SCORE);

        double total = clamp(raw * regime.scoreMultiplier(), 0, MAX_SCORE);

        return new BullishScore(round1(trend.points()), round1(rs.points()), round1(momentum.points()),
                round1(volume.points()), round1(structure), round1(pattern.points()),
                round1(breakout.points()), round1(plan.points()), round1(raw),
                regime.scoreMultiplier(), round1(total), classify(total, cfg));
    }

    /**
     * Price structure, 10 points - the quality of the price action itself, independent of where the
     * moving averages sit.
     *
     * <p>Three inputs. Swing structure (4) is HH+HL. Higher-timeframe agreement (3) is
     * {@link TrendAlignment}, reused rather than re-derived, and is what keeps a daily breakout
     * inside a falling weekly trend from scoring like a clean one. Volatility contraction (3)
     * compares the last ten sessions' ATR against the thirty before them: a range that is tightening
     * is a stock coiling, and it is the single most common precursor to a real move.
     */
    static double priceStructurePoints(IndicatorSnapshot s, Trend trend,
                                       TrendAlignment.Alignment higherTimeframes) {
        double structurePoints = switch (trend.structure()) {
            case "HH+HL" -> 4;
            case "LH+LL" -> 0;
            default -> 1.5;
        };

        double alignmentPoints = switch (higherTimeframes.verdict()) {
            case "ALIGNED_UP" -> 3;
            case "COUNTER" -> 0;
            default -> 1.5;
        };

        double contraction = volatilityContraction(s);
        double contractionPoints;
        if (Double.isNaN(contraction)) contractionPoints = 1.5;
        else if (contraction <= 0.75) contractionPoints = 3;
        else if (contraction <= 0.95) contractionPoints = 2;
        else if (contraction <= 1.15) contractionPoints = 1;
        else contractionPoints = 0;

        return structurePoints + alignmentPoints + contractionPoints;
    }

    /**
     * Recent ATR as a fraction of the preceding period's ATR. Below 1 means the range is
     * contracting. NaN when there is not enough history for both windows.
     */
    static double volatilityContraction(IndicatorSnapshot s) {
        int recent = 10;
        int prior = 30;
        if (s.n < recent + prior + 15) return Double.NaN;
        double recentAtr = s.atr14[s.n - 1];
        double priorAtr = s.atr14[s.n - 1 - recent];
        if (!(priorAtr > 0)) return Double.NaN;

        // Compared as a ratio of ATR percentages so a stock whose price rose 40% over the window
        // does not read as "expanding" purely because the same volatility costs more rupees.
        double recentPct = s.close[s.n - 1] > 0 ? recentAtr / s.close[s.n - 1] : Double.NaN;
        double priorPct = s.close[s.n - 1 - recent] > 0 ? priorAtr / s.close[s.n - 1 - recent] : Double.NaN;
        if (Double.isNaN(recentPct) || Double.isNaN(priorPct) || priorPct <= 0) return Double.NaN;
        return recentPct / priorPct;
    }

    /** Section 12's bands. */
    public static String classify(double total, BullishConfig cfg) {
        if (total >= cfg.getScoreAPlus()) return "A+ BULLISH";
        if (total >= cfg.getScoreStrong()) return "STRONG BULLISH";
        if (total >= cfg.getScoreWatchlist()) return "BULLISH WATCHLIST";
        if (total >= cfg.getScoreNeutral()) return "NEUTRAL / DEVELOPING";
        return "WEAK";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    static double round1(double v) {
        return Double.isNaN(v) ? Double.NaN : Math.round(v * 10.0) / 10.0;
    }
}
