package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishScoreEngine.BullishScore;
import com.javawarriors.breakout.bullish.BullishTrendAnalyzer.Trend;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;
import com.javawarriors.breakout.bullish.MomentumAnalyzer.Momentum;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;
import com.javawarriors.breakout.bullish.PullbackAnalyzer.SetupStage;
import com.javawarriors.breakout.bullish.RelativeStrengthAnalyzer.RelativeStrength;
import com.javawarriors.breakout.bullish.TradePlanCalculator.TradePlan;
import com.javawarriors.breakout.bullish.VolumeAnalyzer.VolumeProfile;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.timeframe.TrendAlignment;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs one stock through every analyzer, in the one order the dependencies allow.
 *
 * <p>The sequence is not arbitrary. Pattern detection has to precede breakout analysis, because a
 * pattern contributes a candidate breakout level. Breakout analysis has to precede overextension,
 * which measures distance past that level. Both precede the trade plan, which places the stop under
 * whichever of the swing low, pattern invalidation or breakout level is highest. Only then can the
 * score be totalled, and only after the score exists can the trade status be decided - and even
 * then the score is the last thing the decision tree consults.
 *
 * <p>Everything reads from a single {@link IndicatorSnapshot}, built once at the top. No analyzer
 * touches the network, and none of them recomputes an EMA.
 */
public final class BullishStockAnalyzer {

    private final BullishConfig cfg;

    public BullishStockAnalyzer(BullishConfig cfg) {
        this.cfg = cfg;
    }

    /**
     * @param nifty50  benchmark bars for relative strength; may be null
     * @param nifty500 benchmark bars for relative strength; may be null
     * @throws IllegalArgumentException when there is not enough history to score the stock
     */
    public BullishStockResult analyze(String symbol, String name, String universe, String sector,
                                      List<Bar> bars, List<Bar> nifty50, List<Bar> nifty500,
                                      Regime regime) {
        if (bars == null || bars.size() < cfg.getMinBars()) {
            throw new IllegalArgumentException(symbol + ": need >= " + cfg.getMinBars()
                    + " bars, got " + (bars == null ? 0 : bars.size()));
        }

        IndicatorSnapshot s = IndicatorSnapshot.of(symbol, bars);

        Trend trend = BullishTrendAnalyzer.analyze(s);
        TrendAlignment.Alignment higherTimeframes = BullishTrendAnalyzer.higherTimeframes(s);
        Momentum momentum = MomentumAnalyzer.analyze(s);
        RelativeStrength rs = RelativeStrengthAnalyzer.analyze(s, nifty50, nifty500);
        VolumeProfile volume = VolumeAnalyzer.analyze(s, cfg);

        ChartPattern pattern = ChartPatternDetector.detect(s, cfg);
        BreakoutStage breakout = BreakoutStageAnalyzer.analyze(s, pattern, cfg);
        Overextension over = OverextensionAnalyzer.analyze(s, breakout, cfg);
        TradePlan plan = TradePlanCalculator.calculate(s, breakout, pattern, over, cfg);

        BullishScore score = BullishScoreEngine.score(s, trend, rs, momentum, volume, pattern,
                breakout, plan, higherTimeframes, regime, cfg);

        // Calling an entry needs the full daily stack AND no higher timeframe contradicting it.
        // Which of the two failed is worth naming rather than collapsing to a boolean: a stock can
        // be textbook-stacked on the daily and still sit inside a falling weekly trend, and that is
        // the common case here — reporting it as "price is not above its moving averages" would be
        // both wrong and visibly at odds with the explanation printed next to it.
        String trendIssue = null;
        if (!trend.fullyStacked() || !trend.priceAboveEma200()) {
            trendIssue = "price is not above all three rising moving averages";
        } else if (!higherTimeframes.supportsLong()) {
            trendIssue = "the daily stack is aligned but the weekly or monthly trend still points down";
        }

        SetupStage setup = PullbackAnalyzer.classify(s, breakout, over, regime, score.total(),
                plan.present() ? plan.riskReward() : Double.NaN, trendIssue, cfg);

        double changePct = s.prevClose > 0 ? (s.price / s.prevClose - 1) * 100 : Double.NaN;

        return new BullishStockResult(symbol, name, universe, sector, s.price, s.prevClose, changePct,
                trend, higherTimeframes, momentum, rs, volume, pattern, breakout, over, plan, setup,
                score, explain(trend, rs, momentum, volume, pattern, breakout, over, setup));
    }

    /**
     * The plain-English "why", assembled from the readings that actually fired rather than from a
     * template. Section 14 asks for this, and it is also the honest check on the score: if the
     * sentence reads thin, the score should be low.
     */
    static String explain(Trend trend, RelativeStrength rs, Momentum momentum, VolumeProfile volume,
                          ChartPattern pattern, BreakoutStage breakout, Overextension over,
                          SetupStage setup) {
        List<String> parts = new ArrayList<>();

        if (trend.fullyStacked()) parts.add("price is above a rising EMA20 > EMA50 > EMA200 stack");
        else if (trend.priceAboveEma200()) parts.add("price holds above its 200 EMA but the stack is not yet aligned");
        else parts.add("price is still below its 200 EMA");

        if ("HH+HL".equals(trend.structure())) parts.add("structure is higher highs and higher lows");

        if (!Double.isNaN(rs.excess3mPct())) {
            parts.add(String.format("3-month relative strength is %+.0f%% against the broad market",
                    rs.excess3mPct()));
        }

        if (pattern.isPresent()) {
            String name = pattern.name().toLowerCase();
            parts.add(String.format("%s %s is in place (%.0f%% confidence)", article(name), name,
                    pattern.confidence()));
        }

        if (breakout.confirmed()) {
            parts.add(breakout.explanation().replaceFirst("^Closed", "it closed")
                    .replaceAll("\\.$", ""));
        } else if (breakout.nearBreakout()) {
            parts.add("it is coiling just under its breakout level");
        }

        if ("ACCUMULATION".equals(volume.label())) parts.add("volume is being traded into strength");
        else if ("BREAKOUT VOLUME".equals(volume.label())) parts.add("volume expanded on the move");
        else if ("DISTRIBUTION".equals(volume.label())) parts.add("volume is heavier on down days");

        if (momentum.adx() >= 25) parts.add(String.format("ADX %.0f confirms a real trend", momentum.adx()));

        String body = String.join(", ", parts);
        String sentence = Character.toUpperCase(body.charAt(0)) + body.substring(1) + ".";

        if (!over.isClean()) sentence += " " + over.explanation();
        if (!setup.tradeable()) sentence += " " + setup.reason();
        return sentence;
    }

    /** "a" or "an" for a pattern name. Only the vowel rule is needed - every name here is plain. */
    private static String article(String word) {
        return word.isEmpty() || "aeiou".indexOf(word.charAt(0)) < 0 ? "a" : "an";
    }
}
