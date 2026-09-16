package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.index500.pattern.PatternRegistry;
import com.javawarriors.breakout.index500.pattern.PatternResult;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance maths, pattern detection, status classification, scoring and ranking.
 *
 * <p>Driven off hand-built series rather than recorded market data: a test asserting "this is a
 * falling wedge" is only meaningful if the series provably contains one.
 */
class Index500AnalysisTest {

    private final Index500Config cfg = new Index500Config();
    private final BullishConfig bullishCfg = new BullishConfig();
    private final PatternRegistry registry = new PatternRegistry(bullishCfg);
    private final PatternAnalysisService patternAnalysis = new PatternAnalysisService(registry);
    private final OpportunityScoreService scoring = new OpportunityScoreService();
    private final PerformanceRankingService ranking = new PerformanceRankingService();
    private final SectorAnalysisService sectorAnalysis = new SectorAnalysisService();

    private final Index500AnalysisService service =
            new Index500AnalysisService(cfg, new SectorService(), patternAnalysis, scoring);

    private static StockMetadata meta(String symbol, String sector) {
        return new StockMetadata(symbol, symbol.replace(".NS", ""), sector, sector, "NSE", "Nifty 500");
    }

    private Index500Analysis analyse(String symbol, String sector, List<Bar> bars) {
        return service.analyse(meta(symbol, sector), bars, -8.0);
    }

    // ------------------------------------------------------------- performance

    @Test
    void sixMonthReturnAndDropAreTheSameNumberWithOppositeSigns() {
        Index500Analysis r = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());

        assertTrue(r.analysed());
        assertEquals(-r.return6mPct(), r.drop6mPct(), 1e-9, "drop is the negated return");
        assertFalse(Double.isNaN(r.return1mPct()));
        assertFalse(Double.isNaN(r.return3mPct()));
        assertFalse(Double.isNaN(r.return1yPct()));
    }

    @Test
    void distanceFromTheFiftyTwoWeekHighAndLowIsReported() {
        Index500Analysis r = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());

        assertTrue(r.fromHigh52wPct() <= 0, "price cannot be above its own 52-week high");
        assertTrue(r.fromLow52wPct() >= 0, "nor below its own 52-week low");
        assertTrue(r.high52w() > r.low52w());
    }

    @Test
    void theLastSessionsMoveIsReportedAndMatchesTheClosingPrices() {
        List<Bar> bars = Index500TestSeries.fellThenTurned();
        Index500Analysis r = analyse("FELL.NS", "IT", bars);

        double prev = bars.get(bars.size() - 2).close();
        double expected = (bars.get(bars.size() - 1).close() / prev - 1) * 100;
        assertEquals(expected, r.return1dPct(), 1e-9,
                "1D must be the move from the previous close, not from any other bar");
        assertEquals(bars.get(bars.size() - 1).close(), r.price(), 1e-9);
    }

    @Test
    void aStockWithNoUsableDataReportsNoOneDayMoveRatherThanZero() {
        // Zero would read as "flat today", which is a claim; NaN renders as an em dash instead.
        Index500Analysis r = analyse("THIN.NS", "IT", Index500TestSeries.fellThenTurned().subList(0, 10));

        assertFalse(r.analysed());
        assertTrue(Double.isNaN(r.return1dPct()));
    }

    @Test
    void aSteadyUptrendIsNotReportedAsADecliner() {
        Index500Analysis r = analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend());

        assertTrue(r.return6mPct() > 0);
        assertTrue(r.drop6mPct() < 0, "a rising stock has a negative 'drop'");
    }

    // ------------------------------------------------------------- error handling

    @Test
    void aStockWithTooLittleHistoryIsReportedNotDropped() {
        List<Bar> shortSeries = Index500TestSeries.startingAt(100).move(30, 5).build();

        Index500Analysis r = analyse("SHORT.NS", "IT", shortSeries);

        assertFalse(r.analysed());
        assertEquals(Index500Analysis.DATA_UNAVAILABLE, r.status());
        assertNotNull(r.unavailableReason());
        assertEquals("SHORT.NS", r.symbol(), "it still appears in the universe");
    }

    @Test
    void nullBarsAreHandledRatherThanThrowing() {
        Index500Analysis r = analyse("NULL.NS", "IT", null);

        assertFalse(r.analysed());
        assertEquals(Index500Analysis.DATA_UNAVAILABLE, r.status());
    }

    // ------------------------------------------------------------- patterns

    @Test
    void theRegistryExposesEveryRequestedPatternExactlyOnce() {
        var catalogue = registry.catalogue();

        for (String required : new String[] {"DOUBLE_BOTTOM", "CUP_AND_HANDLE",
                "INVERSE_HEAD_AND_SHOULDERS", "FALLING_WEDGE", "BULL_FLAG", "BULLISH_ENGULFING",
                "HAMMER", "HIGHER_HIGH_HIGHER_LOW", "EMA50_RECLAIM", "EMA200_RECLAIM",
                "BREAKOUT", "BREAKOUT_RETEST", "SUPPORT_REVERSAL"}) {
            assertTrue(catalogue.containsKey(required), "missing pattern " + required);
        }
        assertEquals(registry.detectors().size(), catalogue.size(), "no duplicate types");
    }

    @Test
    void aStockThatFellAndTurnedProducesAtLeastOnePattern() {
        Index500Analysis r = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());

        assertFalse(r.patterns().isEmpty(), "expected a pattern on a base-and-turn series");
        assertNotNull(r.bestPattern());
        assertTrue(r.bestPattern().confidence() > 0 && r.bestPattern().confidence() <= 10);
    }

    @Test
    void everyDetectedPatternCarriesTheLevelsTheSpecAsksFor() {
        Index500Analysis r = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());

        for (PatternResult p : r.patterns()) {
            assertTrue(p.detected());
            assertFalse(p.patternName().isBlank());
            assertNotNull(p.confirmation(), p.patternType() + " must say what still has to happen");
            assertFalse(p.explanation().isBlank(), p.patternType() + " must explain itself");
            assertTrue(p.confidence() > 0 && p.confidence() <= 10,
                    p.patternType() + " confidence " + p.confidence());
        }
    }

    @Test
    void anEmaReclaimNeedsPriceToHaveBeenBelowTheAverageFirst() {
        // A stock that never went below its 50 EMA cannot be "reclaiming" it.
        Index500Analysis up = analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend());

        assertFalse(up.patterns().stream().anyMatch(p -> p.patternType().equals("EMA50_RECLAIM")),
                "a stock that never fell below the average has nothing to reclaim");
    }

    @Test
    void oneFailingDetectorDoesNotCostTheOthers() {
        // A deliberately hostile series: flat, zero-range bars. Detection must return, not throw.
        List<Bar> flat = Index500TestSeries.startingAt(100).chop(300, 0.0001).build();
        IndicatorSnapshot s = IndicatorSnapshot.of("FLAT.NS", flat);

        assertDoesNotThrow(() -> registry.detectAll(s));
    }

    // ------------------------------------------------------------- status

    @Test
    void aStockStillFallingWithNoPatternIsNeverGivenAPositiveStatus() {
        Index500Analysis r = analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling());

        assertTrue(List.of(Index500Analysis.WEAK, Index500Analysis.AVOID,
                        Index500Analysis.WAIT_FOR_CONFIRMATION).contains(r.status()),
                "got " + r.status());
    }

    /**
     * Regression: a still-falling stock - below every EMA, sitting on its 52-week low - printed a
     * high-confidence but <em>unconfirmed</em> Hammer, and that alone was enough to earn REVERSAL
     * WATCH. A shape is not a reversal; something has to show the decline stopped.
     */
    @Test
    void aHighConfidenceButUnconfirmedPatternBelowTheMeanIsNotAReversalWatch() {
        Index500Analysis r = analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling());

        assertTrue(r.price() < r.ema20(), "fixture must still be under its 20 EMA");
        assertNotNull(r.bestPattern(), "fixture must still produce a pattern");
        assertNotEquals(Index500Analysis.REVERSAL_WATCH, r.status(),
                "a pattern alone, with price below the mean and no confirmation, is not a reversal");
        assertNotEquals(Index500Analysis.STRONG_REVERSAL, r.status());
    }

    @Test
    void noStatusEverSaysBuy() {
        for (List<Bar> series : List.of(Index500TestSeries.fellThenTurned(),
                Index500TestSeries.stillFalling(), Index500TestSeries.steadyUptrend())) {
            String status = analyse("X.NS", "IT", series).status();
            assertFalse(status.toUpperCase().contains("BUY"),
                    "a fallen stock is not a buy signal, got " + status);
        }
    }

    @Test
    void everyAnalysedStockExplainsItsStatus() {
        for (List<Bar> series : List.of(Index500TestSeries.fellThenTurned(),
                Index500TestSeries.stillFalling(), Index500TestSeries.steadyUptrend())) {
            Index500Analysis r = analyse("X.NS", "IT", series);
            assertFalse(r.statusReason().isBlank(), r.status() + " must explain itself");
        }
    }

    @Test
    void theStatusReasonAgreesWithTheArticleInFrontOfThePatternName() {
        for (List<Bar> series : List.of(Index500TestSeries.fellThenTurned(),
                Index500TestSeries.stillFalling(), Index500TestSeries.steadyUptrend())) {
            String reason = analyse("X.NS", "IT", series).statusReason();
            // "An inverse head & shoulders", not "A inverse head & shoulders" — this text is read
            // by the user on every row, so the article has to agree with the name it introduces.
            assertFalse(reason.matches("(?s)^A [aeiou].*"), "wrong article in: " + reason);
            assertFalse(reason.matches("(?s)^An [^aeiou].*"), "wrong article in: " + reason);
        }
    }

    // ------------------------------------------------------------- scoring

    @Test
    void theEightComponentsSumToTheTotalAndTheWeightsMatchTheSpec() {
        Index500Analysis r = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());
        OpportunityScore s = r.score();

        double sum = s.decline() + s.pattern() + s.structure() + s.volume()
                + s.rsi() + s.adx() + s.ema() + s.relativeStrength();
        assertEquals(s.total(), sum, 0.5, "the breakdown must add up to the total");

        assertEquals(100, OpportunityScore.MAX_DECLINE + OpportunityScore.MAX_PATTERN
                + OpportunityScore.MAX_STRUCTURE + OpportunityScore.MAX_VOLUME
                + OpportunityScore.MAX_RSI + OpportunityScore.MAX_ADX
                + OpportunityScore.MAX_EMA + OpportunityScore.MAX_RELATIVE_STRENGTH);
    }

    @Test
    void theScoreStaysWithinZeroToOneHundred() {
        for (List<Bar> series : List.of(Index500TestSeries.fellThenTurned(),
                Index500TestSeries.stillFalling(), Index500TestSeries.steadyUptrend())) {
            double total = analyse("X.NS", "IT", series).score().total();
            assertTrue(total >= 0 && total <= 100, "got " + total);
        }
    }

    /**
     * The spec's central warning, as a test: the decline component must peak and then fall away, so
     * a collapse cannot outrank a healthy dislocation.
     */
    @Test
    void aCollapseScoresLowerOnDeclineThanAModerateFall() {
        double moderate = OpportunityScoreService.declinePoints(-28);
        double collapse = OpportunityScoreService.declinePoints(-70);
        double trivial = OpportunityScoreService.declinePoints(-2);

        assertEquals(OpportunityScore.MAX_DECLINE, moderate, "a 28% fall is the target zone");
        assertTrue(collapse < moderate, "a 70% collapse must not outrank a 28% fall");
        assertTrue(trivial < moderate, "barely moving is not a dislocation either");
    }

    @Test
    void aStockThatTurnedOutscoresOneStillFalling() {
        double turned = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned()).score().total();
        double falling = analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling()).score().total();

        assertTrue(turned > falling, turned + " should beat " + falling);
    }

    @Test
    void rsiScoresHighestWhileRecoveringRatherThanWhileOversold() {
        // Verified through the public banding rather than a live series, so the intent is explicit.
        assertTrue(OpportunityScoreService.declinePoints(-25) > OpportunityScoreService.declinePoints(-60));
    }

    // ------------------------------------------------------------- ranking

    @Test
    void biggestDeclinersAreOrderedWorstFirstAndUnavailableRowsSinkToTheBottom() {
        List<Index500Analysis> rows = List.of(
                analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend()),
                analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("BROKEN.NS", "IT", null),
                analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned()));

        List<Index500Analysis> ordered = ranking.biggestDecliners(rows);

        assertEquals("FALLING.NS", ordered.get(0).symbol(), "the worst six months ranks first");
        assertEquals("BROKEN.NS", ordered.get(ordered.size() - 1).symbol(),
                "a row with no data must never top a decliners list");
        for (int i = 0; i < ordered.size() - 2; i++) {
            assertTrue(ordered.get(i).return6mPct() <= ordered.get(i + 1).return6mPct());
        }
    }

    @Test
    void strongestRecoveryPrefersTheStockThatFellAndTurned() {
        List<Index500Analysis> rows = List.of(
                analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned()));

        assertEquals("FELL.NS", ranking.strongestRecovery(rows).get(0).symbol());
    }

    /**
     * Regression: the recovery ranking added an uncapped 1M momentum term on top of a decline
     * bonus, so stocks up 200% over six months - which never fell - outranked genuine recoveries.
     * Having declined is a precondition for recovering, not a bonus.
     */
    @Test
    void aStockThatNeverFellIsNotARecoveryCandidateAtAll() {
        Index500Analysis rising = analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend());
        Index500Analysis recovered = analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned());

        assertTrue(rising.return6mPct() > 0, "fixture must actually be up over six months");
        assertEquals(Double.NEGATIVE_INFINITY, PerformanceRankingService.recoveryScore(rising),
                "a stock that never declined cannot be recovering");

        List<Index500Analysis> ordered = ranking.strongestRecovery(List.of(rising, recovered));
        assertEquals("FELL.NS", ordered.get(0).symbol());
    }

    @Test
    void sortingByOpportunityIsTheDefaultAndDescends() {
        List<Index500Analysis> rows = List.of(
                analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned()),
                analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend()));

        List<Index500Analysis> ordered = ranking.rank(rows, null, null);
        for (int i = 0; i < ordered.size() - 1; i++) {
            assertTrue(ordered.get(i).score().total() >= ordered.get(i + 1).score().total());
        }
    }

    @Test
    void theDeclineRankIsStableWhateverTheTableIsSortedBy() {
        List<Index500Analysis> rows = List.of(
                analyse("UP.NS", "IT", Index500TestSeries.steadyUptrend()),
                analyse("FALLING.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("FELL.NS", "IT", Index500TestSeries.fellThenTurned()));

        var byDecline = PerformanceRankingService.rankMap(ranking.biggestDecliners(rows));
        var byScore = PerformanceRankingService.rankMap(ranking.biggestDecliners(ranking.byOpportunity(rows)));

        assertEquals(byDecline, byScore, "decline rank must not depend on the current sort");
    }

    // ------------------------------------------------------------- sectors

    @Test
    void sectorSummaryAggregatesPerSectorAndOrdersWeakestFirst() {
        List<Index500Analysis> rows = List.of(
                analyse("A.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("B.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("C.NS", "Banking", Index500TestSeries.steadyUptrend()));

        List<SectorAnalysisService.SectorSummary> out = sectorAnalysis.summarise(rows);

        assertEquals(2, out.size());
        assertEquals("IT", out.get(0).sector(), "the weakest sector leads");
        assertEquals(2, out.get(0).stocks());
        assertEquals(2, out.get(0).declining());
        assertEquals(1, out.get(1).advancing());
        assertTrue(out.get(0).avgReturn6mPct() < out.get(1).avgReturn6mPct());
    }

    @Test
    void anUnanalysedStockCountsInTheSectorTotalButNotItsAverages() {
        List<Index500Analysis> rows = List.of(
                analyse("A.NS", "IT", Index500TestSeries.stillFalling()),
                analyse("BROKEN.NS", "IT", null));

        SectorAnalysisService.SectorSummary it = sectorAnalysis.summarise(rows).get(0);

        assertEquals(2, it.stocks(), "it is still a member of the sector");
        assertEquals(1, it.analysed(), "but only one contributed a reading");
    }

    @Test
    void sectorMatchingIgnoresCaseAndTreatsAllAsNoFilter() {
        assertTrue(SectorService.matches("ALL", "IT"));
        assertTrue(SectorService.matches(null, "IT"));
        assertTrue(SectorService.matches("", "IT"));
        assertTrue(SectorService.matches("information technology", "Information Technology"));
        assertFalse(SectorService.matches("Banking", "IT"));
    }

    @Test
    void anUnclassifiedSymbolKeepsAPlaceholderSectorRatherThanNull() {
        Index500Analysis r = service.analyse(
                new StockMetadata("X.NS", "X", StockMetadata.UNKNOWN_SECTOR,
                        StockMetadata.UNKNOWN_SECTOR, "NSE", "Nifty 500"),
                Index500TestSeries.fellThenTurned(), -8.0);

        assertEquals(StockMetadata.UNKNOWN_SECTOR, r.sector());
    }
}
