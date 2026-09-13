package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishScoreEngine.BullishScore;
import com.javawarriors.breakout.bullish.BullishTrendAnalyzer.Trend;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.MomentumAnalyzer.Momentum;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;
import com.javawarriors.breakout.bullish.PullbackAnalyzer.SetupStage;
import com.javawarriors.breakout.bullish.RelativeStrengthAnalyzer.RelativeStrength;
import com.javawarriors.breakout.bullish.TradePlanCalculator.TradePlan;
import com.javawarriors.breakout.bullish.VolumeAnalyzer.VolumeProfile;
import com.javawarriors.breakout.timeframe.TrendAlignment;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One stock's complete bullish assessment, and the single place its JSON shape is defined.
 *
 * <p>Both the ranked list and the single-symbol lookup serialise through {@link #toRow()}, for the
 * same reason {@code BreakoutResult} does: two independently written row builders drift, and the
 * frontend then has to know which endpoint it is reading.
 */
public record BullishStockResult(
        String symbol, String name, String universe, String sector,
        double price, double previousClose, double changePct,
        Trend trend, TrendAlignment.Alignment higherTimeframes, Momentum momentum,
        RelativeStrength relativeStrength, VolumeProfile volume, ChartPattern pattern,
        BreakoutStage breakout, Overextension overextension, TradePlan tradePlan,
        SetupStage setup, BullishScore score, String whyBullish) {

    /**
     * Ranking order: score first, and only then how close the setup is to being actionable.
     *
     * <p>A strict two-key comparator rather than a score plus a small bonus. A bonus folded into
     * the score would have to be smaller than the 0.1 the score is displayed to, or the list stops
     * descending by the Score column the user is reading - and at that size it would lose to
     * floating-point noise. Comparing the keys in order keeps both properties: the column always
     * descends, and genuine ties resolve toward the tradeable setup.
     */
    public static final java.util.Comparator<BullishStockResult> BY_RANK =
            java.util.Comparator.comparingDouble((BullishStockResult r) -> r.score().total())
                    .thenComparingInt(BullishStockResult::stageRank)
                    .reversed();

    /** Higher is closer to being actionable. Used only to break a score tie. */
    private int stageRank() {
        return switch (setup.tradeStatus()) {
            case PullbackAnalyzer.BUY_NOW -> 5;
            case PullbackAnalyzer.WAIT_FOR_RETEST -> 4;
            case PullbackAnalyzer.WAIT_FOR_BREAKOUT -> 3;
            case PullbackAnalyzer.WAIT_FOR_PULLBACK -> 2;
            case PullbackAnalyzer.AVOID_CHASING -> 1;
            default -> 0;
        };
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("name", name);
        row.put("universe", universe);
        if (sector != null) row.put("sector", sector);
        row.put("price", price);
        row.put("previousClose", previousClose);
        row.put("changePct", changePct);

        row.put("score", score.total());
        row.put("classification", score.classification());
        row.put("tradeStatus", setup.tradeStatus());
        row.put("setupStage", setup.stage());
        row.put("tradeable", setup.tradeable());
        row.put("statusReason", setup.reason());
        row.put("whyBullish", whyBullish);

        row.put("scoreBreakdown", score.toRow());
        row.put("trend", trend.toRow());
        row.put("higherTimeframes", higherTimeframes.toRow());
        row.put("momentum", momentum.toRow());
        row.put("relativeStrength", relativeStrength.toRow());
        row.put("volume", volume.toRow());
        row.put("pattern", pattern.toRow());
        row.put("breakout", breakout.toRow());
        row.put("overextension", overextension.toRow());
        row.put("tradePlan", tradePlan.toRow());

        // Flat mirrors of the handful of fields the ranked table renders in every row, so the
        // table does not have to reach three levels into the nested objects for each cell.
        Map<String, Object> flat = new LinkedHashMap<>();
        flat.put("patternName", pattern.name());
        flat.put("trendLabel", trend.label());
        flat.put("rsLabel", relativeStrength.label());
        flat.put("rs3mPct", relativeStrength.excess3mPct());
        flat.put("rsi", momentum.rsi());
        flat.put("adx", momentum.adx());
        flat.put("volumeRatio", volume.currentRatio());
        flat.put("entry", tradePlan.present() ? tradePlan.entry() : null);
        flat.put("stopLoss", tradePlan.present() ? tradePlan.stopLoss() : null);
        flat.put("target", tradePlan.present() ? tradePlan.target1() : null);
        flat.put("riskReward", tradePlan.present() ? tradePlan.riskReward() : null);
        row.put("summary", flat);

        return row;
    }
}
