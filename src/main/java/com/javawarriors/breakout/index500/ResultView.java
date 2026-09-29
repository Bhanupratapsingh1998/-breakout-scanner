package com.javawarriors.breakout.index500;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The summary tiles above the table, each of which is also a filter.
 *
 * <h2>Why the count and the filter are the same predicate</h2>
 *
 * <p>The tiles used to be counted by five inline lambdas in the controller, and the table was
 * filtered by a separate set of query parameters. That is two definitions of "is a breakout", and
 * the moment they disagree the tile reads 11 and the table it opens shows 12 rows - a discrepancy
 * a reader cannot explain and has no way to investigate.
 *
 * <p>Here each view owns one {@link #matches} and both the number and the rows come from it, so
 * clicking a tile showing <i>n</i> can only ever produce <i>n</i> rows. The relationship is
 * enforced rather than maintained.
 *
 * <h2>Counted before the view, filtered after</h2>
 *
 * <p>{@link #countsFor} runs over the rows the other filters left, <em>not</em> over the rows the
 * active view left. That is what keeps the tiles usable once one is selected: with "Breakouts"
 * active the other four still show what selecting them would give, so the tiles work as a set of
 * alternatives rather than collapsing to the one already chosen and four zeroes.
 */
public enum ResultView {

    /** Everything the other filters matched. Selecting it is how a tile is deselected. */
    ALL("Matching", "matched", r -> true),

    DECLINERS("Down over 6M", "declinerCount",
            r -> r.analysed() && r.return6mPct() < 0),

    PATTERNS("Showing a pattern", "patternCount",
            r -> r.bestPattern() != null),

    REVERSALS("Reversals", "reversalCount",
            r -> Index500Analysis.STRONG_REVERSAL.equals(r.status())
                    || Index500Analysis.REVERSAL_WATCH.equals(r.status())),

    BREAKOUTS("Breakouts", "breakoutCount",
            r -> Index500Analysis.BREAKOUT_CONFIRMED.equals(r.status())
                    || Index500Analysis.BREAKOUT_CANDIDATE.equals(r.status()));

    private final String label;
    private final String countKey;
    private final Predicate<Index500Analysis> test;

    ResultView(String label, String countKey, Predicate<Index500Analysis> test) {
        this.label = label;
        this.countKey = countKey;
        this.test = test;
    }

    public String label() {
        return label;
    }

    /**
     * The field this view's count is published under.
     *
     * <p>The existing names are kept - {@code declinerCount}, {@code patternCount} and the rest -
     * because the dashboard cards already read them. A tidier scheme would have meant renaming a
     * response field to no benefit.
     */
    public String countKey() {
        return countKey;
    }

    public boolean matches(Index500Analysis row) {
        return test.test(row);
    }

    /**
     * Every view's count over the same set of rows, plus the unavailable tally.
     *
     * <p>Unavailable rows are counted but belong to no view: they have no return, no pattern and
     * no status to test, so they appear only under {@code ALL} and only when the caller asked for
     * them.
     */
    public static Map<String, Object> countsFor(List<Index500Analysis> rows) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ResultView v : values()) {
            if (v == ALL) continue;
            out.put(v.countKey, rows.stream().filter(v::matches).count());
        }
        out.put("unavailableCount", rows.stream().filter(r -> !r.analysed()).count());
        return out;
    }

    /** The view a query names, or {@link #ALL} for null, blank or unrecognised input. */
    public static ResultView ofParam(String name) {
        if (name == null || name.isBlank()) return ALL;
        for (ResultView v : values()) {
            if (v.name().equalsIgnoreCase(name.trim())) return v;
        }
        return ALL;
    }
}
