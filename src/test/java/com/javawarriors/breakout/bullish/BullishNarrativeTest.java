package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.BullishTrendAnalyzer.Trend;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.MomentumAnalyzer.Momentum;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;
import com.javawarriors.breakout.bullish.PullbackAnalyzer.SetupStage;
import com.javawarriors.breakout.bullish.RelativeStrengthAnalyzer.RelativeStrength;
import com.javawarriors.breakout.bullish.VolumeAnalyzer.VolumeProfile;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The plain-English "why" shown at the top of every expanded row.
 *
 * <p>It is assembled from whichever readings fired, so the risk is not that a clause is missing but
 * that the same clause arrives twice by two different routes.
 */
class BullishNarrativeTest {

    private static final String CHASE =
            "Price has already made the move: 21% past the breakout level. "
                    + "Entering here pays for someone else's gain.";

    private static Trend trend() {
        return new Trend(true, true, true, true, "HH+HL", true, true, 1, 3, 9, "STRONG UPTREND", 20);
    }

    private static Overextension extended(String explanation) {
        return new Overextension("SEVERE", 1.4, 4.0, 11.3, 21.0, 64.4, "STRETCHED",
                List.of("ATR"), explanation);
    }

    private static String explain(Overextension over, SetupStage setup) {
        return BullishStockAnalyzer.explain(
                trend(),
                new RelativeStrength(18.9, 32.8, 20.0, 33.9, 1, 2, 3, true, "STRONG", 15),
                new Momentum(18.9, 30.3, 22.9, 64.4, 29.5, 3, "STRONG", 9.5),
                new VolumeProfile(1e6, 9e5, 1.87, 1.4, 1.3, true, "ACCUMULATION", 9.5),
                ChartPattern.none(),
                new BreakoutStage("NONE", "No breakout", false, false, false,
                        Double.NaN, Double.NaN, -1, 0, Double.NaN, Double.NaN, Double.NaN, 0, ""),
                over, setup);
    }

    @Test
    void anOverextendedStockIsToldOffOnceRatherThanTwice() {
        // The setup stage takes its reason from the overextension reading, so both clauses carry
        // the identical sentence and appending both printed it back to back.
        String sentence = explain(extended(CHASE),
                new SetupStage("EXTENDED", "AVOID CHASING", false, CHASE));

        assertTrue(sentence.contains(CHASE), "the warning must still be there");
        assertEquals(sentence.indexOf(CHASE), sentence.lastIndexOf(CHASE),
                "and it must appear exactly once:\n" + sentence);
    }

    @Test
    void twoDifferentWarningsAreBothKept() {
        String stageReason = "The nearest logical stop is 15.5% away, beyond the 10% maximum.";
        String sentence = explain(extended(CHASE),
                new SetupStage("EXTENDED", "AVOID CHASING", false, stageReason));

        assertTrue(sentence.contains(CHASE));
        assertTrue(sentence.contains(stageReason),
                "a distinct second reason must not be swallowed:\n" + sentence);
    }

    @Test
    void aCleanTradeableSetupGetsNoWarningClauseAtAll() {
        String sentence = explain(
                new Overextension("NONE", 0.1, 1.0, 7.7, 5.4, 53.8, "CLEAN", List.of(),
                        "Price is close enough to its own mean that this is not a chase."),
                new SetupStage("BREAKOUT CONFIRMED", "BUY NOW", true, "Entry is called here."));

        assertFalse(sentence.contains("not a chase"), "a clean reading is not a caveat worth adding");
        assertFalse(sentence.contains("Entry is called here"));
        assertTrue(sentence.endsWith("."), "still a well-formed sentence:\n" + sentence);
    }
}
