package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.breakout.StretchFromMean;
import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How much of the move has already happened - section 9, and the single most important guard in
 * this engine.
 *
 * <p>It earns no points of its own. That is deliberate: overextension is not a component to be
 * traded off against a strong pattern or good relative strength, because a stock four ATRs above
 * its 20 EMA is a bad entry <em>however</em> good the setup is. Instead this feeds the trade-status
 * decision in {@link PullbackAnalyzer}, where SEVERE forces AVOID CHASING and MODERATE forces
 * WAIT FOR PULLBACK regardless of score.
 *
 * <p>Five independent readings are taken because each catches a different way of being late, and
 * they genuinely disagree. {@link StretchFromMean} - reused wholesale from the breakout scanner -
 * catches the slow grind that never trips a short-window flag. The ATR distance from the 20 EMA
 * catches the vertical week that has not yet moved the 50 EMA. The distance above the breakout
 * level catches the stock that broke out cleanly three weeks and 20% ago. RSI catches the blow-off.
 */
public final class OverextensionAnalyzer {

    /**
     * @param level  NONE / MODERATE / SEVERE
     * @param flags  the specific readings that fired, for display
     */
    public record Overextension(String level, double atrsAboveEma20, double pctAboveEma20,
                                double pctAboveEma50, double pctAboveBreakout, double rsi,
                                String stretchRating, List<String> flags, String explanation) {

        public boolean isSevere() {
            return "SEVERE".equals(level);
        }

        public boolean isModerate() {
            return "MODERATE".equals(level);
        }

        /** True when price is close enough to its own mean that entering here is not chasing. */
        public boolean isClean() {
            return "NONE".equals(level);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", level);
            row.put("atrsAboveEma20", atrsAboveEma20);
            row.put("pctAboveEma20", pctAboveEma20);
            row.put("pctAboveEma50", pctAboveEma50);
            if (!Double.isNaN(pctAboveBreakout)) row.put("pctAboveBreakout", pctAboveBreakout);
            row.put("rsi", rsi);
            row.put("stretchRating", stretchRating);
            row.put("flags", flags);
            row.put("explanation", explanation);
            return row;
        }
    }

    private OverextensionAnalyzer() {
    }

    public static Overextension analyze(IndicatorSnapshot s, BreakoutStage breakout, BullishConfig cfg) {
        double atrsAbove20 = s.lastAtr > 0 ? (s.price - s.lastEma20) / s.lastAtr : Double.NaN;
        double pctAbove20 = s.lastEma20 > 0 ? (s.price / s.lastEma20 - 1) * 100 : Double.NaN;
        double pctAbove50 = s.lastEma50 > 0 ? (s.price / s.lastEma50 - 1) * 100 : Double.NaN;
        double pctAboveBreakout = breakout.confirmed() && breakout.hasLevel()
                ? breakout.distancePct() : Double.NaN;

        // The breakout scanner's own stretch reading, so the two tabs never disagree about whether
        // the same stock has run too far.
        double close63 = s.n > IndicatorSnapshot.BARS_3M
                ? s.close[s.n - 1 - IndicatorSnapshot.BARS_3M] : Double.NaN;
        StretchFromMean stretch = StretchFromMean.analyze(s.price, s.lastEma50, s.lastEma200,
                s.lastAtr, close63);

        List<String> flags = new ArrayList<>();
        int severe = 0;
        int moderate = 0;

        if (!Double.isNaN(atrsAbove20) && atrsAbove20 >= cfg.getSevereAtrsFromEma20()) {
            flags.add(String.format("%.1f ATRs above the 20 EMA", atrsAbove20));
            severe++;
        } else if (!Double.isNaN(atrsAbove20) && atrsAbove20 >= cfg.getExtendedAtrsFromEma20()) {
            flags.add(String.format("%.1f ATRs above the 20 EMA", atrsAbove20));
            moderate++;
        }

        if (s.lastRsi >= cfg.getSevereRsi()) {
            flags.add(String.format("RSI %.0f", s.lastRsi));
            severe++;
        } else if (s.lastRsi >= cfg.getExtendedRsi()) {
            flags.add(String.format("RSI %.0f", s.lastRsi));
            moderate++;
        }

        if (!Double.isNaN(pctAboveBreakout)) {
            double limit = cfg.getMaxExtensionFromBreakout() * 100;
            if (pctAboveBreakout >= limit * 1.75) {
                flags.add(String.format("%.0f%% past the breakout level", pctAboveBreakout));
                severe++;
            } else if (pctAboveBreakout >= limit) {
                flags.add(String.format("%.0f%% past the breakout level", pctAboveBreakout));
                moderate++;
            }
        }

        if (stretch.isChase()) {
            flags.add(String.format("%.1f ATRs and %.0f%% above the 50 EMA", stretch.atrsAboveEma50(),
                    stretch.pctAboveEma50()));
            severe++;
        } else if ("EXTENDED".equals(stretch.rating())) {
            flags.add(String.format("%.0f%% above the 50 EMA", stretch.pctAboveEma50()));
            moderate++;
        }

        // A three-month run this large is itself a reading, independent of where the EMAs sit.
        if (!Double.isNaN(stretch.runUp63d()) && stretch.runUp63d() >= 60) {
            flags.add(String.format("%.0f%% run over three months", stretch.runUp63d()));
            moderate++;
        }

        // Any single severe reading is enough — these do not need to agree, because each one alone
        // describes a price that has already travelled. Two moderate readings compound into one.
        String level = severe >= 1 ? "SEVERE" : (moderate >= 2 ? "SEVERE" : moderate == 1 ? "MODERATE" : "NONE");

        String explanation = switch (level) {
            case "SEVERE" -> "Price has already made the move: " + String.join(", ", flags)
                    + ". Entering here pays for someone else's gain.";
            case "MODERATE" -> "Somewhat extended (" + String.join(", ", flags)
                    + ") — better entered on a pullback than at market.";
            default -> "Price is close enough to its own mean that this is not a chase.";
        };

        return new Overextension(level, atrsAbove20, pctAbove20, pctAbove50, pctAboveBreakout,
                s.lastRsi, stretch.rating(), List.copyOf(flags), explanation);
    }
}
