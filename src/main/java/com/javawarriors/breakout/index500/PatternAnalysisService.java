package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.index500.pattern.PatternRegistry;
import com.javawarriors.breakout.index500.pattern.PatternResult;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Runs the detectors over a stock and turns what they found into a status.
 *
 * <p>The status is a decision tree, and its order is the argument. A confirmed breakout outranks a
 * reversal pattern, a reversal pattern outranks a mere bounce, and every one of them outranks the
 * size of the fall - which appears nowhere in the tree. That is deliberate and is the spec's
 * central warning: a stock down 40% is not a buying opportunity, so "has fallen a long way" can
 * never by itself produce a positive status.
 *
 * <p>There is no BUY. The strongest thing this feature will say is STRONG REVERSAL, which means
 * several independent readings agree the decline has stopped - not that the stock will rise.
 */
@Service
public class PatternAnalysisService {

    private final PatternRegistry registry;

    public PatternAnalysisService(PatternRegistry registry) {
        this.registry = registry;
    }

    public List<PatternResult> detect(IndicatorSnapshot s) {
        return registry.detectAll(s);
    }

    public PatternResult best(List<PatternResult> patterns) {
        return patterns.isEmpty() ? null : patterns.get(0);
    }

    /** @return {@code [status, reason]} */
    public String[] classify(IndicatorSnapshot s, List<PatternResult> patterns, PatternResult best,
                             Index500Config cfg) {
        boolean fell = !Double.isNaN(s.return6m) && s.return6m * 100 <= -cfg.getDeclineThresholdPct();
        boolean aboveEma20 = s.price > s.lastEma20;
        boolean aboveEma50 = s.price > s.lastEma50;
        boolean volumeConfirms = s.volumeRatio >= cfg.getConfirmingVolumeRatio();
        boolean momentumTurning = s.lastRsi > cfg.getOversoldRsi();
        boolean strongPattern = best != null && best.confidence() >= cfg.getStrongPatternConfidence();
        boolean confirmed = best != null && PatternResult.CONFIRMED.equals(best.confirmation());

        boolean hasBreakout = has(patterns, "BREAKOUT");
        boolean hasRetest = has(patterns, "BREAKOUT_RETEST");
        boolean breakoutConfirmed = patterns.stream()
                .anyMatch(p -> ("BREAKOUT".equals(p.patternType()) || "BREAKOUT_RETEST".equals(p.patternType()))
                        && PatternResult.CONFIRMED.equals(p.confirmation()));

        if (breakoutConfirmed) {
            return new String[] {Index500Analysis.BREAKOUT_CONFIRMED,
                    hasRetest ? "Cleared a real level and is holding the retest above it."
                              : "Closed clear of a real resistance level."};
        }
        if ((hasBreakout || hasRetest) && aboveEma20) {
            return new String[] {Index500Analysis.BREAKOUT_CANDIDATE,
                    "Coiled under a level it has not cleared on a close yet."};
        }

        // A reversal needs the fall, the pattern, and evidence the fall has stopped. All three.
        if (best != null && fell && strongPattern && confirmed && aboveEma20 && volumeConfirms
                && momentumTurning) {
            return new String[] {Index500Analysis.STRONG_REVERSAL,
                    String.format("Down %.0f%% over six months, but a %s has confirmed on %.1fx volume"
                                    + " with price back above its 20 EMA and RSI at %.0f.",
                            -s.return6m * 100, best.patternName().toLowerCase(), s.volumeRatio, s.lastRsi)};
        }
        // A reversal watch needs evidence the decline has actually paused - price back over its
        // 20 EMA, or the pattern confirmed. A high-confidence shape on its own is not enough: a
        // stock below every average and sitting on its 52-week low can print a textbook hammer
        // while still falling, and calling that a reversal is the exact error this screen exists
        // to avoid. Without that evidence it falls through to WAIT FOR CONFIRMATION, which is what
        // an unconfirmed pattern actually means.
        boolean declineStopped = aboveEma20 || confirmed;
        if (fell && best != null && declineStopped) {
            return new String[] {Index500Analysis.REVERSAL_WATCH,
                    String.format("Down %.0f%% over six months with a %s forming - worth watching, but"
                                    + " not yet confirmed on every measure.",
                            -s.return6m * 100, best.patternName().toLowerCase())};
        }
        if (fell && aboveEma50 && !Double.isNaN(s.return1m) && s.return1m > 0) {
            return new String[] {Index500Analysis.RECOVERY,
                    "Fell over six months but has reclaimed its 50 EMA and is up over the last month."};
        }
        if (best != null) {
            return new String[] {Index500Analysis.WAIT_FOR_CONFIRMATION,
                    String.format("%s %s is present but nothing has confirmed it yet.",
                            article(best.patternName()), best.patternName().toLowerCase())};
        }
        // Nothing found. Separate "quietly weak" from "still actively falling".
        boolean nearLow = nearFiftyTwoWeekLow(s);
        if (fell && nearLow && !aboveEma20) {
            return new String[] {Index500Analysis.AVOID,
                    "Still making new lows with no pattern and no sign the decline has stopped."};
        }
        return new String[] {Index500Analysis.WEAK,
                "No pattern detected and price is not yet above its shorter moving averages."};
    }

    static boolean nearFiftyTwoWeekLow(IndicatorSnapshot s) {
        double low = s.lowestLow(IndicatorSnapshot.BARS_52W);
        return low > 0 && (s.price / low - 1) * 100 <= 10;
    }

    private static boolean has(List<PatternResult> patterns, String type) {
        return patterns.stream().anyMatch(p -> p.patternType().equals(type));
    }

    /** "a" or "an", agreeing with the pattern name that follows it. */
    private static String article(String patternName) {
        char first = Character.toLowerCase(patternName.charAt(0));
        return "aeiou".indexOf(first) >= 0 ? "An" : "A";
    }
}
