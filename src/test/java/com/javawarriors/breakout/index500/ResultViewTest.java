package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.BullishConfig;
import com.javawarriors.breakout.index500.pattern.PatternRegistry;
import com.javawarriors.breakout.model.Bar;
import com.javawarriors.breakout.wick.DoubleBottomDetector;
import com.javawarriors.breakout.wick.WickReversalConfig;
import com.javawarriors.breakout.wick.WickReversalDetector;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The summary tiles, which are also the table's filters.
 *
 * <p>The property worth testing is not what any single view matches - it is that a tile's number
 * and the rows that tile opens can never disagree. A reader who clicks "11 breakouts" and counts
 * twelve rows has no way to tell which number is lying, so the agreement is asserted directly,
 * over real analyses rather than hand-set statuses.
 */
class ResultViewTest {

    private final Index500Config cfg = new Index500Config();
    private final Index500AnalysisService service = new Index500AnalysisService(
            cfg, new SectorService(),
            new PatternAnalysisService(new PatternRegistry(new BullishConfig())),
            new OpportunityScoreService(),
            new WickReversalDetector(), new DoubleBottomDetector(), new WickReversalConfig());

    /** A spread of real shapes, so the views have something of each kind to sort. */
    private List<Index500Analysis> universe() {
        List<Index500Analysis> rows = new ArrayList<>();
        rows.add(analyse("FELL.NS", Index500TestSeries.fellThenTurned()));
        rows.add(analyse("UP.NS", Index500TestSeries.steadyUptrend()));
        rows.add(analyse("FELL2.NS", Index500TestSeries.fellThenTurned()));
        rows.add(analyse("UP2.NS", Index500TestSeries.steadyUptrend()));
        // An unavailable row belongs to no view but must not break the counting.
        rows.add(Index500Analysis.unavailable(
                new StockMetadata("DEAD.NS", "Dead", "IT", "IT", "NSE", "Nifty 500"), "no bars"));
        return rows;
    }

    private Index500Analysis analyse(String symbol, List<Bar> bars) {
        return service.analyse(
                new StockMetadata(symbol, symbol.replace(".NS", ""), "IT", "IT", "NSE", "Nifty 500"),
                bars, -8.0);
    }

    @Test
    void everyTileCountEqualsTheRowsThatTileOpens() {
        List<Index500Analysis> rows = universe();
        Map<String, Object> counts = ResultView.countsFor(rows);

        for (ResultView v : ResultView.values()) {
            if (v == ResultView.ALL) continue;
            long shown = rows.stream().filter(v::matches).count();
            assertEquals(counts.get(v.countKey()), shown,
                    v + ": the tile says " + counts.get(v.countKey()) + " but opens " + shown
                            + " rows. The count and the filter have drifted apart.");
        }
    }

    @Test
    void allShowsEverythingIncludingUnavailableRows() {
        List<Index500Analysis> rows = universe();

        // ALL is how a tile is deselected, so it must return the caller to exactly what they had -
        // including the rows that carry no metrics.
        assertEquals(rows.size(), rows.stream().filter(ResultView.ALL::matches).count());
        assertTrue(rows.stream().anyMatch(r -> !r.analysed()));
    }

    @Test
    void anUnavailableRowIsInNoNarrowingView() {
        Index500Analysis dead = Index500Analysis.unavailable(
                new StockMetadata("DEAD.NS", "Dead", "IT", "IT", "NSE", "Nifty 500"), "no bars");

        // It has no return, no pattern and no status to test. Letting it into a view would put a
        // row of dashes under a heading that claims to describe it.
        for (ResultView v : ResultView.values()) {
            if (v == ResultView.ALL) continue;
            assertFalse(v.matches(dead), v + " must not match a row with no data");
        }
    }

    @Test
    void countsAreAlsoPublishedUnderTheNamesTheDashboardAlreadyReads() {
        Map<String, Object> counts = ResultView.countsFor(universe());

        // The dashboard cards and the tab header read these keys. Renaming them to something
        // tidier would have been a breaking change for no gain.
        assertTrue(counts.containsKey("declinerCount"));
        assertTrue(counts.containsKey("patternCount"));
        assertTrue(counts.containsKey("reversalCount"));
        assertTrue(counts.containsKey("breakoutCount"));
        assertTrue(counts.containsKey("unavailableCount"));
        assertEquals(1L, counts.get("unavailableCount"));
    }

    @Test
    void decliningMeansNegativeNotMerelyFlat() {
        List<Index500Analysis> rows = universe();

        // The tile reads "Down over 6M". A stock that has gone exactly nowhere is not down, and
        // including it would make the tile's number exceed the rows a reader would call decliners.
        for (Index500Analysis r : rows.stream().filter(ResultView.DECLINERS::matches).toList()) {
            assertTrue(r.return6mPct() < 0, r.symbol() + " is in DECLINERS at " + r.return6mPct() + "%");
        }
    }

    @Test
    void anUnknownOrAbsentViewFallsBackToAll() {
        // The view arrives from a query string. A stale bookmark naming a view that no longer
        // exists should show the whole table, not an error or an empty one.
        assertEquals(ResultView.ALL, ResultView.ofParam(null));
        assertEquals(ResultView.ALL, ResultView.ofParam(""));
        assertEquals(ResultView.ALL, ResultView.ofParam("   "));
        assertEquals(ResultView.ALL, ResultView.ofParam("NOT_A_VIEW"));

        assertEquals(ResultView.BREAKOUTS, ResultView.ofParam("BREAKOUTS"));
        assertEquals(ResultView.BREAKOUTS, ResultView.ofParam("breakouts"));
        assertEquals(ResultView.DECLINERS, ResultView.ofParam(" decliners "));
    }

    @Test
    void reversalsAndBreakoutsCoverBothOfTheirStatuses() {
        // Each tile spans two statuses, which is why the single-value status parameter could not
        // express them and this enum exists.
        assertTrue(ResultView.REVERSALS.matches(withStatus(Index500Analysis.STRONG_REVERSAL)));
        assertTrue(ResultView.REVERSALS.matches(withStatus(Index500Analysis.REVERSAL_WATCH)));
        assertFalse(ResultView.REVERSALS.matches(withStatus(Index500Analysis.RECOVERY)));

        assertTrue(ResultView.BREAKOUTS.matches(withStatus(Index500Analysis.BREAKOUT_CONFIRMED)));
        assertTrue(ResultView.BREAKOUTS.matches(withStatus(Index500Analysis.BREAKOUT_CANDIDATE)));
        assertFalse(ResultView.BREAKOUTS.matches(withStatus(Index500Analysis.WEAK)));
    }

    /** A real analysis, relabelled - the record is immutable, so status is set by rebuilding it. */
    private Index500Analysis withStatus(String status) {
        Index500Analysis base = analyse("X.NS", Index500TestSeries.fellThenTurned());
        return new Index500Analysis(base.metadata(), base.analysed(), status, base.statusReason(),
                base.unavailableReason(), base.price(), base.return1dPct(), base.return1mPct(),
                base.return3mPct(), base.return6mPct(), base.return1yPct(), base.drop6mPct(),
                base.fromHigh52wPct(), base.fromLow52wPct(), base.high52w(), base.low52w(),
                base.ema20(), base.ema50(), base.ema200(), base.emaStatus(), base.rsi(), base.adx(),
                base.atr(), base.volume(), base.avgVolume20(), base.volumeRatio(), base.support(),
                base.resistance(), base.patterns(), base.bestPattern(), base.wickSignals(),
                base.score());
    }
}
