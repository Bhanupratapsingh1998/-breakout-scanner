package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The wick-reversal engine.
 *
 * <p>The tests are grouped the way the feature is argued: first that merging two candles is a
 * faithful operation, then that each of the four conditions actually excludes something, then the
 * follow-through states, then the scoring.
 */
class WickReversalTest {

    private final WickReversalConfig cfg = new WickReversalConfig();
    private final WickReversalDetector detector = new WickReversalDetector();

    /** Two candles — the setup exactly as the reference images draw it. */
    private WickSignal detect(List<Bar> bars) {
        return detect(bars, 2);
    }

    private WickSignal detect(List<Bar> bars, int count) {
        return detect(bars, count, Direction.BULLISH);
    }

    private WickSignal detect(List<Bar> bars, int count, Direction direction) {
        return detector.detect("TEST.NS", "Test Ltd.", "IT", "15m", bars, count, direction, cfg);
    }

    // ------------------------------------------------------------- the merge

    @Test
    void mergingTwoCandlesKeepsTheSecondClosesAndWidensTheExtremes() {
        List<Bar> base = WickTestSeries.declineThenRejection();
        List<Bar> merged = CandleMerger.rolling(base, 2);

        assertEquals(base.size(), merged.size(), "indices must stay comparable with the base series");
        for (int i = 1; i < base.size(); i++) {
            Bar b = base.get(i), p = base.get(i - 1), m = merged.get(i);
            assertEquals(b.close(), m.close(), 1e-9, "close is the second candle's, untouched");
            assertEquals(p.open(), m.open(), 1e-9, "open is the first candle's");
            assertEquals(Math.max(p.high(), b.high()), m.high(), 1e-9);
            assertEquals(Math.min(p.low(), b.low()), m.low(), 1e-9);
            assertEquals(p.volume() + b.volume(), m.volume(), 1e-9);
        }
    }

    @Test
    void theMergeLeavesClosesAloneSoCloseBasedTestsStayHonest() {
        // Everything downstream that reasons about trend does it with closes. Because the merge
        // only ever widens opens, highs and lows, a close-based test handed the merged series is
        // asking its question of the real price history — which is what lets the shared hammer
        // detector run on merged candles at all.
        List<Bar> base = WickTestSeries.declineThenRejection();
        List<Bar> merged = CandleMerger.rolling(base, 2);

        for (int i = 1; i < base.size(); i++) {
            assertEquals(base.get(i).close(), merged.get(i).close(), 1e-9);
        }
    }

    @Test
    void anEmptySeriesMergesToAnEmptySeries() {
        assertTrue(CandleMerger.rolling(List.of(), 2).isEmpty());
    }

    // --------------------------------------------------------- the four gates

    @Test
    void theCanonicalSetupFires() {
        WickSignal s = detect(WickTestSeries.declineThenRejection());

        assertNotNull(s, "a drop taken straight back after a decline is the whole pattern");
        assertEquals(0, s.barsAgo(), "it is the most recent pair");
        assertEquals("30m", s.mergedInterval(), "two 15m candles are one 30m candle");
        assertTrue(s.wickToBody() >= 4, "wick should dwarf the body, got " + s.wickToBody());
        assertTrue(s.closePosition() >= 0.8, "close should sit high in the range, got " + s.closePosition());
        assertTrue(s.lowerWick() > s.upperWick() * 5, "the rejection is on the low side");
    }

    @Test
    void twoUpCandlesDoNotFireEvenWhenTheyMergeIntoALongLowerWick() {
        // Same outline, different story: nobody was pushed down and beaten back.
        List<Bar> bars = WickTestSeries.startingAt(120).drift(48, -0.35).twoGreenPair().build();
        assertNull(detect(bars), "composition must be a down candle then an up candle");
    }

    @Test
    void theSameShapeInTheMiddleOfARallyIsRejected() {
        // A bought-up dip in a rising stock has the identical outline. What separates it is where
        // it happens, and measuring that across the sell-off candle itself gets it wrong: the drop
        // swamps a short window and every pullback starts looking like a bottom.
        assertNull(detect(WickTestSeries.rallyThenSameShape()),
                "a long lower wick into strength is not the setup in the images");
    }

    @Test
    void theContextTestLooksBeforeTheSellOffRatherThanAcrossIt() {
        // Identical pair, identical shape; only what came before differs. If the gate measured
        // across the sell-off, both of these would fire.
        assertNotNull(detect(WickTestSeries.declineThenRejection()), "after a fall: a reversal");
        assertNull(detect(WickTestSeries.rallyThenSameShape()), "after a rally: a pullback");
    }

    @Test
    void aRecoveryThatDoesNotFinishTheJobIsRejected() {
        // Raising the bar to 95% of the range turns the canonical setup - which closes at 92% - into
        // a near miss. This is the gate that insists the second candle takes the drop back, not
        // merely stops it.
        WickReversalConfig strict = new WickReversalConfig();
        strict.setMinClosePosition(0.95);

        assertNotNull(detect(WickTestSeries.declineThenRejection()), "fires at the default");
        assertNull(detector.detect("TEST.NS", "Test Ltd.", "IT", "15m",
                WickTestSeries.declineThenRejection(), 2, Direction.BULLISH, strict),
                "and not at the stricter setting");
    }

    @Test
    void aSeriesShorterThanTheMinimumIsNotGuessedAt() {
        List<Bar> bars = WickTestSeries.startingAt(120).drift(5, -0.5).rejectionPair(100_000).build();
        assertNull(detect(bars), "too few candles to judge context, so no signal rather than a weak one");
    }

    // ------------------------------------------------- three candles instead of two

    @Test
    void aThreeCandleGroupMergesAllThreeAndIsLabelledAsSuch() {
        WickSignal s = detect(WickTestSeries.declineThenSlowRejection(), 3);

        assertNotNull(s, "two candles down then one taking it all back is the same story, slower");
        assertEquals(3, s.candles());
        assertEquals("45m", s.mergedInterval(), "three 15m candles are one 45m candle");
        assertTrue(s.wickToBody() >= 4, "wick should still dwarf the body, got " + s.wickToBody());
    }

    @Test
    void aSlowRejectionIsInvisibleToThePairScanAndOnlyShowsAtThree() {
        // This is the reason the group size is worth having as a filter rather than a constant:
        // the two halves of this fall are both down candles, so no adjacent pair inside it can
        // pass the composition gate, and the last two merge into a body far too fat for a hammer.
        List<Bar> bars = WickTestSeries.declineThenSlowRejection();

        assertNull(detect(bars, 2), "no pair inside a two-candle fall qualifies");
        assertNotNull(detect(bars, 3), "but the whole group does");
    }

    @Test
    void theFirstCandleMustStillBeDownAndTheLastUpAtThree() {
        // at() rather than detect(): detect scans a whole window and would happily find a different,
        // valid group nearby, which would prove nothing about the one under test.
        List<Bar> upFirst = WickTestSeries.startingAt(120).drift(47, -0.35)
                .bar(100.0, 100.5, 99.8, 100.3, 100_000)    // up — disqualifies the group
                .bar(100.3, 100.4, 95.0, 95.5, 100_000)
                .bar(95.5, 100.2, 95.0, 100.0, 100_000)
                .build();
        assertNull(at(upFirst, 3), "a group that opens with an up candle is not a rejection");

        // Only the first candle differs; everything the other three gates look at is unchanged.
        List<Bar> downFirst = WickTestSeries.startingAt(120).drift(47, -0.35)
                .bar(100.3, 100.5, 99.8, 100.0, 100_000)    // down
                .bar(100.0, 100.4, 95.0, 95.5, 100_000)
                .bar(95.5, 100.2, 95.0, 100.0, 100_000)
                .build();
        assertNotNull(at(downFirst, 3), "flip only the first candle and the same group fires");
    }

    /** Runs the single group of {@code count} candles ending on the last bar. */
    private WickSignal at(List<Bar> bars, int count) {
        return detector.at("TEST.NS", "Test Ltd.", "IT", "15m",
                bars, CandleMerger.rolling(bars, count), bars.size() - 1, count,
                Direction.BULLISH, cfg);
    }

    @Test
    void detectAllReturnsAtMostOneSignalPerConfiguredGroupSize() {
        List<WickSignal> all = detector.detectAll("TEST.NS", "Test Ltd.", "IT", "15m",
                WickTestSeries.declineThenRejection(), cfg);

        assertFalse(all.isEmpty(), "the canonical setup must be found by at least one group size");
        assertTrue(all.size() <= cfg.getCandleCounts().size());
        assertEquals(all.size(), all.stream().map(WickSignal::candles).distinct().count(),
                "one signal per size at most, never two of the same");
        for (WickSignal s : all) {
            assertTrue(cfg.getCandleCounts().contains(s.candles()), "unexpected size " + s.candles());
        }
    }

    @Test
    void groupingKeepsClosesIntactAtEveryGroupSize() {
        // The property the whole design rests on has to hold for three as well as two, or the
        // shared hammer detector's context test stops asking about real price history.
        List<Bar> base = WickTestSeries.declineThenSlowRejection();
        for (int count : List.of(2, 3, 4)) {
            List<Bar> merged = CandleMerger.rolling(base, count);
            assertEquals(base.size(), merged.size());
            for (int i = 0; i < base.size(); i++) {
                assertEquals(base.get(i).close(), merged.get(i).close(), 1e-9,
                        "close changed at group size " + count);
            }
        }
    }

    @Test
    void aThreeCandleMergeSpansAllThreeCandlesExtremes() {
        List<Bar> base = WickTestSeries.declineThenSlowRejection();
        List<Bar> merged = CandleMerger.rolling(base, 3);
        int i = base.size() - 1;
        Bar a = base.get(i - 2), b = base.get(i - 1), c = base.get(i);

        assertEquals(a.open(), merged.get(i).open(), 1e-9);
        assertEquals(c.close(), merged.get(i).close(), 1e-9);
        assertEquals(Math.max(a.high(), Math.max(b.high(), c.high())), merged.get(i).high(), 1e-9);
        assertEquals(Math.min(a.low(), Math.min(b.low(), c.low())), merged.get(i).low(), 1e-9);
        assertEquals(a.volume() + b.volume() + c.volume(), merged.get(i).volume(), 1e-9);
    }

    // ------------------------------------------------------- the bearish mirror

    @Test
    void aRallySoldStraightBackIsABearishSignal() {
        WickSignal s = detect(WickTestSeries.rallyThenBearishRejection(), 2, Direction.BEARISH);

        assertNotNull(s, "buyers pushed price up and lost it back - the setup upside down");
        assertEquals(WickSignal.BEARISH, s.direction());
        assertFalse(s.bullish());
        assertTrue(s.upperWick() > s.lowerWick() * 5, "the rejection is on the high side");
        assertTrue(s.wickToBody() >= 4, "and the wick still dwarfs the body: " + s.wickToBody());
    }

    @Test
    void eachDirectionRejectsTheOthersSetup() {
        // The same bars cannot be both. Asking for the wrong direction must return nothing rather
        // than reinterpreting a bottom as a top.
        assertNull(detect(WickTestSeries.declineThenRejection(), 2, Direction.BEARISH),
                "a rejection at a low is not a bearish signal");
        assertNull(detect(WickTestSeries.rallyThenBearishRejection(), 2, Direction.BULLISH),
                "a rejection at a high is not a bullish signal");
    }

    @Test
    void aBearishShapeAfterADeclineIsRejectedByTheContextGate() {
        // A bounce sold back inside a downtrend has the outline of a top without being one — the
        // mirror of the pullback case the bullish side already refuses.
        assertNull(detect(WickTestSeries.declineThenBearishShape(), 2, Direction.BEARISH),
                "an upper wick inside a decline is not the end of a rally");
    }

    @Test
    void theBearishLevelsAreTheBullishOnesSwapped() {
        WickSignal bull = detect(WickTestSeries.declineThenRejection());
        WickSignal bear = detect(WickTestSeries.rallyThenBearishRejection(), 2, Direction.BEARISH);
        assertNotNull(bull);
        assertNotNull(bear);

        assertEquals(bull.mergedHigh(), bull.triggerLevel(), 1e-9, "bullish breaks out upward");
        assertEquals(bull.mergedLow(), bull.invalidationLevel(), 1e-9, "and fails through the wick low");
        assertEquals(bear.mergedLow(), bear.triggerLevel(), 1e-9, "bearish breaks down");
        assertEquals(bear.mergedHigh(), bear.invalidationLevel(), 1e-9, "and fails through the wick high");
        assertTrue(bear.riskPct() >= 0, "risk to the level is a distance, never negative");
    }

    @Test
    void aBearishSignalIsConfirmedDownwardAndInvalidatedUpward() {
        List<Bar> base = WickTestSeries.rallyThenBearishRejection();
        WickSignal live = detect(base, 2, Direction.BEARISH);
        assertNotNull(live);
        assertEquals(WickSignal.PENDING, live.status());

        int i = base.size() - 1;
        Bar m = CandleMerger.rolling(base, 2).get(i);

        List<Bar> broke = new ArrayList<>(base);
        broke.add(new Bar(0, m.low(), m.low(), m.low() * 0.98, m.low() * 0.99, 100_000));
        assertEquals(WickSignal.CONFIRMED, WickReversalDetector.statusOf(broke, i, m, false)[0],
                "closing below the candle acts on a bearish rejection");

        List<Bar> failed = new ArrayList<>(base);
        failed.add(new Bar(0, m.high(), m.high() * 1.02, m.high(), m.high() * 1.01, 100_000));
        assertEquals(WickSignal.INVALIDATED, WickReversalDetector.statusOf(failed, i, m, false)[0],
                "closing above the wick high breaks it");
    }

    @Test
    void theSameCloseMeansOppositeThingsToTheTwoDirections() {
        // One bar, two readings: a close above the candle is the bullish thesis working and the
        // bearish one failing. Getting this backwards would report a losing setup as a winner.
        List<Bar> base = WickTestSeries.declineThenRejection();
        int i = base.size() - 1;
        Bar m = CandleMerger.rolling(base, 2).get(i);

        List<Bar> above = new ArrayList<>(base);
        above.add(new Bar(0, m.high(), m.high() * 1.02, m.high(), m.high() * 1.01, 100_000));

        assertEquals(WickSignal.CONFIRMED, WickReversalDetector.statusOf(above, i, m, true)[0]);
        assertEquals(WickSignal.INVALIDATED, WickReversalDetector.statusOf(above, i, m, false)[0]);
    }

    @Test
    void detectAllCoversEveryDirectionAndGroupSizeCombination() {
        List<WickSignal> all = detector.detectAll("TEST.NS", "Test Ltd.", "IT", "15m",
                WickTestSeries.rallyThenBearishRejection(), cfg);

        assertFalse(all.isEmpty());
        assertTrue(all.size() <= cfg.getCandleCounts().size() * cfg.enabledDirections().size());
        assertEquals(all.size(),
                all.stream().map(x -> x.direction() + "/" + x.candles()).distinct().count(),
                "one signal per direction-and-size bucket at most");
        assertTrue(all.stream().anyMatch(x -> WickSignal.BEARISH.equals(x.direction())),
                "a rally sold back must produce at least one bearish row");
    }

    @Test
    void oneDirectionCannotStarveTheOtherOutOfTheResultList() {
        WickReversalConfig capped = new WickReversalConfig();
        capped.setMaxResults(2);

        WickSignal bull = detect(WickTestSeries.declineThenRejection());
        assertNotNull(bull);
        List<WickSignal> found = new ArrayList<>();
        for (int n = 0; n < 4; n++) {
            found.add(like(bull, 2, n, 90 - n));
            found.add(bearishLike(bull, 2, n, 95 - n));
        }
        List<WickSignal> kept = WickReversalService.capPerGroupSize(found, capped);

        assertEquals(2, kept.stream().filter(x -> WickSignal.BULLISH.equals(x.direction())).count());
        assertEquals(2, kept.stream().filter(x -> WickSignal.BEARISH.equals(x.direction())).count(),
                "the bullish list must not shrink because bearish signals exist");
    }

    // ------------------------------------------------------------ what happened since

    @Test
    void aLaterCloseAboveTheHighConfirmsAndOneBelowTheWickInvalidates() {
        List<Bar> base = WickTestSeries.declineThenRejection();
        WickSignal live = detect(base);
        assertNotNull(live);
        assertEquals(WickSignal.PENDING, live.status(), "nothing has happened after the last bar yet");

        assertEquals(WickSignal.CONFIRMED, after(base, live.triggerLevel() * 1.01)[0]);
        assertEquals(WickSignal.INVALIDATED, after(base, live.invalidationLevel() * 0.99)[0]);
    }

    @Test
    void aWickThroughTheLevelIsNotAConfirmation() {
        // Closes decide, not spikes — the same rule the breakout analyzer applies.
        List<Bar> base = new ArrayList<>(WickTestSeries.declineThenRejection());
        WickSignal live = detect(base);
        assertNotNull(live);

        int i = base.size() - 1;
        Bar m = CandleMerger.rolling(base, 2).get(i);
        // A bar that pokes above the high and closes back inside it.
        base.add(new Bar(0, m.close(), m.high() * 1.02, m.close() * 0.99, m.close(), 100_000));

        assertEquals(WickSignal.PENDING, WickReversalDetector.statusOf(base, i, m, true)[0],
                "a spike above the level that closed back inside has not broken it");
    }

    /** Status after appending one bar that closes at {@code close}. */
    private String[] after(List<Bar> base, double close) {
        List<Bar> extended = new ArrayList<>(base);
        int i = base.size() - 1;
        Bar m = CandleMerger.rolling(base, 2).get(i);
        extended.add(new Bar(0, close, Math.max(close, m.high()) * 1.001,
                Math.min(close, m.low()) * 0.999, close, 100_000));
        return WickReversalDetector.statusOf(extended, i, m, true);
    }

    // ------------------------------------------------------------------ scoring

    @Test
    void theFiveComponentsSumToTheReportedScoreAndCannotExceedOneHundred() {
        WickSignal s = detect(WickTestSeries.declineThenRejection());
        assertNotNull(s);

        assertEquals(WickSignal.SCORE_KEYS.size(), s.scoreParts().size());
        double sum = 0;
        for (String key : WickSignal.SCORE_KEYS) {
            Double part = s.scoreParts().get(key);
            assertNotNull(part, key + " must be reported");
            sum += part;
        }
        assertEquals(sum, s.score(), 0.01, "the parts must add up to the total");
        assertTrue(s.score() <= 100 && s.score() > 0, "score out of range: " + s.score());
    }

    @Test
    void aCollapseScoresLowerOnDeclineThanAMeasuredFall() {
        // The decline band peaks and then falls away. A ramp would rank a falling knife highest
        // precisely because it had fallen furthest, which is the opposite of useful.
        double measured = WickReversalDetector.priorMovePoints(3.0);
        double collapse = WickReversalDetector.priorMovePoints(12.0);
        double flat = WickReversalDetector.priorMovePoints(0.5);

        assertTrue(measured > collapse, "a collapse must not outrank a measured fall");
        assertTrue(measured > flat, "and a rejection of nothing must not either");
    }

    @Test
    void aWickThatSetsTheLowOutscoresOneHangingAboveIt() {
        assertTrue(WickReversalDetector.extremePoints(0) > WickReversalDetector.extremePoints(2),
                "rejecting at the low means more than rejecting above it");
    }

    @Test
    void freshSignalsRankAheadOfOlderOnesWhateverTheirScore() {
        WickSignal old = detect(WickTestSeries.declineThenRejection());
        assertNotNull(old);
        // Same signal, aged by three candles and scored higher.
        WickSignal aged = like(old, old.candles(), 3, 100);

        List<WickSignal> rows = new ArrayList<>(List.of(aged, old));
        rows.sort(WickReversalService.ranking());

        assertEquals(0, rows.get(0).barsAgo(),
                "an old level has had time to be taken out; a high score does not make it current");
    }

    @Test
    void oneGroupSizeCannotStarveAnotherOutOfTheResultList() {
        // A single global cap would shrink the two-candle list simply because three-candle groups
        // also exist — a filter silently changing results it has no business touching.
        WickReversalConfig capped = new WickReversalConfig();
        capped.setMaxResults(3);

        WickSignal template = detect(WickTestSeries.declineThenRejection());
        assertNotNull(template);

        List<WickSignal> found = new ArrayList<>();
        for (int n = 0; n < 5; n++) {
            found.add(like(template, 2, n, 90 - n));
            found.add(like(template, 3, n, 90 - n));
        }
        List<WickSignal> kept = WickReversalService.capPerGroupSize(found, capped);

        assertEquals(3, kept.stream().filter(x -> x.candles() == 2).count());
        assertEquals(3, kept.stream().filter(x -> x.candles() == 3).count(),
                "each size keeps its own budget");
        assertEquals(0, kept.get(0).barsAgo(), "and the merged list is still freshest first");
    }

    /** The same again, flipped to bearish, for testing that the cap divides by direction too. */
    private static WickSignal bearishLike(WickSignal t, int candles, int barsAgo, double score) {
        WickSignal b = like(t, candles, barsAgo, score);
        return new WickSignal(b.symbol() + "B", b.companyName(), b.sector(), b.interval(),
                b.candles(), b.mergedInterval(), WickSignal.BEARISH, b.signalTime(), b.barsAgo(),
                b.firstOpen(), b.firstHigh(), b.firstLow(), b.firstClose(),
                b.secondOpen(), b.secondHigh(), b.secondLow(), b.secondClose(),
                b.mergedOpen(), b.mergedHigh(), b.mergedLow(), b.mergedClose(),
                b.bodySize(), b.lowerWick(), b.upperWick(), b.wickToBody(),
                b.closePosition(), b.recoveredPct(), b.priorMoveInRanges(),
                b.distanceFromExtremeRanges(), b.volumeRatio(), b.price(),
                b.invalidationLevel(), b.triggerLevel(), b.riskPct(),
                b.status(), b.statusReason(), b.score(), b.scoreParts());
    }

    /** The same signal with only the fields the ranking and the cap look at changed. */
    private static WickSignal like(WickSignal t, int candles, int barsAgo, double score) {
        return new WickSignal(t.symbol() + candles + barsAgo, t.companyName(), t.sector(),
                t.interval(), candles, t.mergedInterval(), t.direction(), t.signalTime(), barsAgo,
                t.firstOpen(), t.firstHigh(), t.firstLow(), t.firstClose(),
                t.secondOpen(), t.secondHigh(), t.secondLow(), t.secondClose(),
                t.mergedOpen(), t.mergedHigh(), t.mergedLow(), t.mergedClose(),
                t.bodySize(), t.lowerWick(), t.upperWick(), t.wickToBody(),
                t.closePosition(), t.recoveredPct(), t.priorMoveInRanges(),
                t.distanceFromExtremeRanges(), t.volumeRatio(), t.price(),
                t.triggerLevel(), t.invalidationLevel(), t.riskPct(),
                t.status(), t.statusReason(), score, t.scoreParts());
    }

    @Test
    void theMergedLabelNamesTheCandleTheUserWillActuallyLookAt() {
        assertEquals("30m", WickReversalConfig.mergedLabel("15m", 2));
        assertEquals("45m", WickReversalConfig.mergedLabel("15m", 3));
        assertEquals("2h", WickReversalConfig.mergedLabel("60m", 2));
        assertEquals("3h", WickReversalConfig.mergedLabel("60m", 3));
        assertEquals("2-day", WickReversalConfig.mergedLabel("1d", 2));
        assertEquals("3-day", WickReversalConfig.mergedLabel("1d", 3));
        assertEquals("10m", WickReversalConfig.mergedLabel("5m", 2), "the pairing drawn in the images");
        assertEquals("1h30m", WickReversalConfig.mergedLabel("30m", 3), "not every group lands on the hour");
    }
}
