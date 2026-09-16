package com.javawarriors.breakout.index500;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The rankings. Several of them, on purpose.
 *
 * <p>One score cannot answer both "what has fallen hardest" and "what is turning", and collapsing
 * them into a single number hides which question a row is answering. So each ranking is a named
 * ordering over the same analysed set, and the UI picks one.
 */
@Service
public class PerformanceRankingService {

    /** Ranking A - biggest six-month decliners, worst first. */
    public List<Index500Analysis> biggestDecliners(List<Index500Analysis> rows) {
        return sorted(rows, Comparator.comparingDouble(Index500Analysis::return6mPct));
    }

    /**
     * Ranking B - strongest recovery: fell over six months, but is turning now.
     *
     * <p>Scored rather than sorted on one field, because "recovering" is the conjunction of having
     * fallen and of recent strength, and neither half means it alone.
     */
    public List<Index500Analysis> strongestRecovery(List<Index500Analysis> rows) {
        return sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -recoveryScore(r)));
    }

    /**
     * How strongly a stock that <em>fell</em> is now turning.
     *
     * <p>Having fallen is a precondition, not a bonus. An earlier version merely added points for a
     * decline and then added an uncapped 1M momentum term, so the ranking filled up with stocks up
     * 200% over six months - the strongest names in the index, and by definition not recovering
     * from anything. A stock that has not declined is excluded outright, and the momentum term is
     * capped so it can strengthen a candidate without being able to manufacture one.
     */
    static double recoveryScore(Index500Analysis r) {
        if (!r.analysed()) return Double.NEGATIVE_INFINITY;
        if (Double.isNaN(r.return6mPct()) || r.return6mPct() >= 0) return Double.NEGATIVE_INFINITY;

        double score = Math.min(25, -r.return6mPct() * 0.6);        // the fall it is recovering from
        if (!Double.isNaN(r.return1mPct())) {
            score += Math.max(-10, Math.min(25, r.return1mPct() * 1.5));   // the turn itself, bounded
        }
        if (r.price() > r.ema20()) score += 8;
        if (r.price() > r.ema50()) score += 10;
        if (r.rsi() >= 45 && r.rsi() <= 65) score += 8;
        return score;
    }

    /** Ranking C - pattern quality: the strongest technical setups, whatever the decline. */
    public List<Index500Analysis> bestPatterns(List<Index500Analysis> rows) {
        return sorted(rows, Comparator.comparingDouble((Index500Analysis r) ->
                -(r.bestPattern() == null ? -1 : r.bestPattern().confidence())));
    }

    /** Ranking D - the transparent 0-100 opportunity score. */
    public List<Index500Analysis> byOpportunity(List<Index500Analysis> rows) {
        return sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.score().total()));
    }

    /** Named orderings the API exposes through {@code sortBy}. */
    public List<Index500Analysis> rank(List<Index500Analysis> rows, String sortBy, String direction) {
        String key = sortBy == null || sortBy.isBlank() ? "score" : sortBy.trim();
        List<Index500Analysis> out = switch (key) {
            case "decline", "return6m" -> biggestDecliners(rows);
            case "recovery" -> strongestRecovery(rows);
            case "pattern" -> bestPatterns(rows);
            case "return1d" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.return1dPct()));
            case "return1m" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.return1mPct()));
            case "return3m" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.return3mPct()));
            case "return1y" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.return1yPct()));
            case "rsi" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.rsi()));
            case "adx" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.adx()));
            case "volume" -> sorted(rows, Comparator.comparingDouble((Index500Analysis r) -> -r.volumeRatio()));
            case "fromHigh" -> sorted(rows, Comparator.comparingDouble(Index500Analysis::fromHigh52wPct));
            case "symbol" -> sorted(rows, Comparator.comparing(Index500Analysis::symbol));
            default -> byOpportunity(rows);
        };
        if ("asc".equalsIgnoreCase(direction)) {
            List<Index500Analysis> reversed = new ArrayList<>(out);
            java.util.Collections.reverse(reversed);
            return reversed;
        }
        return out;
    }

    /**
     * Sorts while keeping unanalysed rows last.
     *
     * <p>A DATA_UNAVAILABLE row has NaN everywhere, and NaN sorts unpredictably - left alone it can
     * land at the top of a "biggest decliner" list, which would be a lie.
     */
    private static List<Index500Analysis> sorted(List<Index500Analysis> rows,
                                                 Comparator<Index500Analysis> comparator) {
        List<Index500Analysis> analysed = new ArrayList<>();
        List<Index500Analysis> rest = new ArrayList<>();
        for (Index500Analysis r : rows) (r.analysed() ? analysed : rest).add(r);
        analysed.sort(comparator);
        analysed.addAll(rest);
        return analysed;
    }

    /** Assigns 1-based ranks in the given order, for display. */
    public static Map<String, Integer> rankMap(List<Index500Analysis> ordered) {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        int rank = 1;
        for (Index500Analysis r : ordered) ranks.put(r.symbol(), rank++);
        return ranks;
    }
}
