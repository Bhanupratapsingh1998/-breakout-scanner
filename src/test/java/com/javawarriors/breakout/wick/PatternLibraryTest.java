package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The pattern library from {@code src/main/resources/patterns}, encoded as fixtures.
 *
 * <p>Every picture in that folder describes the same mechanism: merge N adjacent candles and read
 * the shape of the result. This class exists to answer a question that is otherwise just an
 * assertion in a chat message - <em>which of those fifteen does the engine already find?</em> Each
 * test below is one picture drawn as bars, so the answer is demonstrated rather than claimed, and
 * a future change that quietly stops finding one of them fails here.
 *
 * <p>The candle values are chosen to match the proportions in each drawing, not sampled from a real
 * stock. That is the point: they pin the geometry the picture shows, so a threshold change that
 * would no longer recognise the drawn pattern is caught.
 */
class PatternLibraryTest {

    private final WickReversalConfig cfg = new WickReversalConfig();
    private final WickReversalDetector detector = new WickReversalDetector();
    private final DoubleBottomDetector doubles = new DoubleBottomDetector();

    /** A long enough decline for the context gate, then the group exactly as drawn. */
    private static WickTestSeries afterADecline() {
        return WickTestSeries.startingAt(120).drift(47, -0.35);
    }

    private WickSignal detect(List<Bar> bars, int count) {
        return detector.detect("PT.NS", "Pattern Ltd.", "IT", "1d", bars, count, Direction.BULLISH, cfg);
    }

    private WickSignal detectBearish(List<Bar> bars, int count) {
        return detector.detect("PT.NS", "Pattern Ltd.", "IT", "1d", bars, count, Direction.BEARISH, cfg);
    }

    // ------------------------------------------------- already covered, two candles

    @Test
    void pt1_dropThenRecovery_mergesIntoALowerWick() {
        // The reference pattern: one red candle, one green that takes it back.
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.2, 95.8, 96.0, 250_000)
                .bar(96.0, 100.0, 95.0, 99.8, 250_000)
                .build();

        WickSignal s = detect(bars, 2);
        assertNotNull(s, "pt1 is the pattern the engine was built from");
        assertEquals(WickSignal.BULLISH, s.direction());
        assertTrue(s.wickToBody() >= 4, "wick should dominate: " + s.wickToBody());
    }

    @Test
    void pt14_twoCandlesThatMergeIntoACrossWithALongTail() {
        // Drawn as red + green = a cross, then a body with a very long lower wick. Open and close
        // land together, so the merged body is zero - the engine must treat that as a perfect
        // rejection rather than dividing by it.
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.1, 95.0, 98.0, 250_000)
                .bar(98.0, 100.2, 97.5, 100.0, 250_000)
                .build();

        WickSignal s = detect(bars, 2);
        assertNotNull(s, "a zero-body merge is a Dragonfly Doji, which the shape test accepts");
        assertEquals(99, s.wickToBody(), "an infinite ratio is reported as the 99 sentinel");
    }

    @Test
    void pt2_and_pt9_aRedCrossThenAStrongGreenCandle() {
        // The cross must be fractionally red for the composition gate: a true doji closes exactly
        // where it opened and is neither side, which is why the drawings colour it red.
        List<Bar> bars = afterADecline()
                .bar(98.1, 98.5, 93.0, 98.0, 250_000)
                .bar(98.0, 100.1, 97.9, 100.0, 300_000)
                .build();

        assertNotNull(detect(bars, 2), "a long-legged cross bought back by the next candle");
    }

    @Test
    void pt7_theBearishMirror_aRallySoldBack() {
        List<Bar> bars = WickTestSeries.startingAt(80).drift(47, 0.35)
                .bar(100.0, 105.0, 99.8, 104.0, 250_000)
                .bar(104.0, 105.2, 99.9, 100.2, 250_000)
                .build();

        WickSignal s = detectBearish(bars, 2);
        assertNotNull(s, "pt7 is the upper-wick case");
        assertEquals(WickSignal.BEARISH, s.direction());
    }

    // ----------------------------------------------- already covered, three candles

    @Test
    void pt6_redThenASmallGreenThenGreen() {
        // Sellers press, stall, then lose it all back. The low belongs to the middle candle, which
        // is only visible once the three are merged.
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.2, 95.5, 96.0, 250_000)
                .bar(96.0, 96.8, 94.0, 96.5, 250_000)
                .bar(96.5, 100.0, 96.3, 99.8, 250_000)
                .build();

        WickSignal s = detect(bars, 3);
        assertNotNull(s, "pt6 is a three-candle lower-wick merge");
        assertEquals(3, s.candles());
        assertNull(detect(bars, 2), "and no pair inside it qualifies on its own");
    }

    @Test
    void pt12_redThenTwoGreens() {
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.2, 96.5, 97.0, 250_000)
                .bar(97.0, 98.7, 95.0, 98.5, 250_000)
                .bar(98.5, 100.1, 98.3, 99.9, 250_000)
                .build();

        WickSignal s = detect(bars, 3);
        assertNotNull(s, "pt12 merges three candles into one long lower wick");
        assertEquals(3, s.candles());
    }

    /** How many of the group's candles bottom within {@code tolerance} of {@code level}. */
    private static int countLowsNear(List<Bar> bars, int from, int to, double level, double tolerance) {
        int n = 0;
        for (int k = from; k <= to; k++) {
            if (Math.abs(bars.get(k).low() - level) / level <= tolerance) n++;
        }
        return n;
    }

    // ------------------------------------------------------- the real gap

    @Test
    void pt10_fourCandlesWorkAlreadyAndOnlyTheConfigWasStoppingThem() {
        // Drawn with four candles merging into one. The merger and the detector are both written
        // against an arbitrary group size, so this needed no new code - only the config list,
        // which decides what detectAll() asks for, had to include it.
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.2, 97.0, 97.5, 250_000)
                .bar(97.5, 98.0, 95.5, 96.0, 250_000)
                .bar(96.0, 97.0, 94.0, 96.8, 250_000)
                .bar(96.8, 100.1, 96.6, 99.9, 250_000)
                .build();

        WickSignal s = detect(bars, 4);
        assertNotNull(s, "four candles merge into a lower wick the same way three do");
        assertEquals(4, s.candles());
        assertEquals("4-day", s.mergedInterval());
        assertTrue(cfg.getCandleCounts().contains(4),
                "and the scan must actually ask for four, or nothing will ever find pt10");
    }

    @Test
    void pt5_and_pt15_theWisNowFoundByReadingTheBaseCandles() {
        List<Bar> bars = wFixture();

        WickSignal s = doubles.detect("PT.NS", "Pattern Ltd.", "IT", "1d",
                bars, 4, Direction.BULLISH, cfg);
        assertNotNull(s, "two matching lows with a bounce between is the W the pictures trace");
        assertEquals(WickSignal.DOUBLE_BOTTOM, s.shape());
        assertEquals(WickSignal.BULLISH, s.direction());
        assertTrue(s.statusReason().contains("tested twice"), s.statusReason());
        assertEquals(95.0, s.invalidationLevel(), 0.2,
                "a double bottom breaks on a close through the shelf, not the group's extreme");
    }

    @Test
    void aSingleDipIsNotAWeventhoughItMergesIdentically() {
        // The discriminating case. Same merged candle as the W fixture - same open, high, low and
        // close - but the level is touched once. If the detector were reading the merge it could
        // not tell these apart, which is exactly why it does not.
        List<Bar> oneTouch = afterADecline()
                .bar(100.0, 100.2, 95.0, 96.0, 250_000)
                .bar(96.0, 98.5, 95.9, 98.0, 250_000)
                .bar(98.0, 98.2, 97.5, 97.8, 250_000)
                .bar(97.8, 100.0, 97.6, 99.8, 250_000)
                .build();

        Bar wMerged = CandleMerger.rolling(wFixture(), 4).get(wFixture().size() - 1);
        Bar dipMerged = CandleMerger.rolling(oneTouch, 4).get(oneTouch.size() - 1);
        assertEquals(wMerged.low(), dipMerged.low(), 1e-9, "identical merged low");
        assertEquals(wMerged.high(), dipMerged.high(), 1e-9, "identical merged high");

        assertNotNull(doubles.detect("PT.NS", "P", "IT", "1d", wFixture(), 4, Direction.BULLISH, cfg));
        assertNull(doubles.detect("PT.NS", "P", "IT", "1d", oneTouch, 4, Direction.BULLISH, cfg),
                "one touch of the level is not a double bottom");
    }

    @Test
    void theMirrorIsAnMattheTopOfARally() {
        List<Bar> bars = WickTestSeries.startingAt(80).drift(47, 0.35)
                .bar(100.0, 105.0, 99.8, 104.5, 250_000)
                .bar(104.5, 104.9, 102.0, 102.5, 250_000)
                .bar(102.5, 105.1, 102.3, 104.0, 250_000)
                .bar(104.0, 104.2, 100.0, 100.2, 250_000)
                .build();

        WickSignal s = doubles.detect("PT.NS", "P", "IT", "1d", bars, 4, Direction.BEARISH, cfg);
        assertNotNull(s, "two matching highs with a dip between is the same shape upside down");
        assertEquals(WickSignal.DOUBLE_TOP, s.shape());
    }

    @Test
    void aWneedsThreeCandlesBecauseAPairCannotHoldABounce() {
        assertNull(doubles.detect("PT.NS", "P", "IT", "1d", wFixture(), 2, Direction.BULLISH, cfg),
                "two feet and a turn between them will not fit in two candles");
    }

    @Test
    void theWickShapeAndTheWareTaggedApartSoTheyCanBeFiltered() {
        WickSignal wick = detect(afterADecline()
                .bar(100.0, 100.2, 95.8, 96.0, 250_000)
                .bar(96.0, 100.0, 95.0, 99.8, 250_000).build(), 2);
        WickSignal w = doubles.detect("PT.NS", "P", "IT", "1d", wFixture(), 4, Direction.BULLISH, cfg);

        assertEquals(WickSignal.REJECTION_WICK, wick.shape());
        assertEquals(WickSignal.DOUBLE_BOTTOM, w.shape());
        assertEquals(WickSignal.DOUBLE_SCORE_KEYS, List.copyOf(w.scoreParts().keySet()));
        assertEquals(WickSignal.SCORE_KEYS, List.copyOf(wick.scoreParts().keySet()));
    }

    /** pt5 / pt15: a level tested twice with a bounce between, then a close away from it. */
    private static List<Bar> wFixture() {
        return afterADecline()
                .bar(100.0, 100.2, 95.0, 96.0, 250_000)   // first foot
                .bar(96.0, 98.5, 95.9, 98.0, 250_000)     // the bounce
                .bar(98.0, 98.2, 95.1, 96.5, 250_000)     // second foot, matching the first
                .bar(96.5, 100.0, 96.3, 99.8, 250_000)    // away from the level
                .build();
    }

    @Test
    void theMergeStillCannotSeeTheSecondFoot() {
        // These two trace a W over the group: two separate lows with a bounce between them. Merged,
        // that is just one candle with a long tail - the second low is invisible. Recognising it
        // needs a test against the base candles, which no current shape performs.
        List<Bar> bars = afterADecline()
                .bar(100.0, 100.2, 95.0, 96.0, 250_000)   // first low
                .bar(96.0, 98.5, 95.8, 98.0, 250_000)     // bounce
                .bar(98.0, 98.2, 95.1, 96.5, 250_000)     // second low, matching the first
                .bar(96.5, 100.0, 96.3, 99.8, 250_000)    // breakout from the W
                .build();

        // The merge does fire - but as a plain lower wick, blind to the fact that the low was
        // tested twice. Its second low simply is not in the merged candle.
        List<Bar> merged = CandleMerger.rolling(bars, 4);
        Bar m = merged.get(bars.size() - 1);
        assertEquals(95.0, m.low(), 1e-9, "the merge keeps only the lower of the two lows");
        assertEquals(2, countLowsNear(bars, bars.size() - 4, bars.size() - 1, m.low(), 0.005),
                "the base candles test that level twice - which is what makes it a W");
    }
}
