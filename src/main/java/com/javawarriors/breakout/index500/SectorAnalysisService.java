package com.javawarriors.breakout.index500;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sector-level performance, so the user can pick <em>where</em> to look before picking what.
 *
 * <p>Both directions are useful and the summary supports both: find the weakest sectors and hunt
 * for the strongest recovery candidates inside them, or find the strongest sectors and take the
 * leaders. Neither is asserted to be the right strategy - the table just makes the choice visible.
 *
 * <p>Median is reported alongside mean deliberately. One stock down 80% drags a sector's average
 * into saying something untrue about the other forty, and the gap between the two numbers is itself
 * the signal that a sector is being distorted by an outlier.
 */
@Service
public class SectorAnalysisService {

    public record SectorSummary(String sector, int stocks, int analysed, int advancing, int declining,
                                double avgReturn1mPct, double avgReturn3mPct, double avgReturn6mPct,
                                double avgReturn1yPct, double medianReturn6mPct,
                                int patternCount, double avgScore) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sector", sector);
            row.put("stocks", stocks);
            row.put("analysed", analysed);
            row.put("advancing", advancing);
            row.put("declining", declining);
            row.put("avgReturn1mPct", avgReturn1mPct);
            row.put("avgReturn3mPct", avgReturn3mPct);
            row.put("avgReturn6mPct", avgReturn6mPct);
            row.put("avgReturn1yPct", avgReturn1yPct);
            row.put("medianReturn6mPct", medianReturn6mPct);
            row.put("patternCount", patternCount);
            row.put("avgScore", avgScore);
            return row;
        }
    }

    /** One row per sector, weakest six months first - the order this feature is built around. */
    public List<SectorSummary> summarise(List<Index500Analysis> rows) {
        Map<String, List<Index500Analysis>> bySector = new TreeMap<>();
        for (Index500Analysis r : rows) {
            bySector.computeIfAbsent(r.sector(), k -> new ArrayList<>()).add(r);
        }

        List<SectorSummary> out = new ArrayList<>();
        for (Map.Entry<String, List<Index500Analysis>> e : bySector.entrySet()) {
            List<Index500Analysis> all = e.getValue();
            List<Index500Analysis> analysed = all.stream().filter(Index500Analysis::analysed).toList();

            int advancing = 0, declining = 0, patterns = 0;
            double sum1m = 0, sum3m = 0, sum6m = 0, sum1y = 0, sumScore = 0;
            List<Double> sixMonth = new ArrayList<>();
            for (Index500Analysis r : analysed) {
                if (r.return6mPct() >= 0) advancing++; else declining++;
                if (r.bestPattern() != null) patterns++;
                sum1m += safe(r.return1mPct());
                sum3m += safe(r.return3mPct());
                sum6m += safe(r.return6mPct());
                sum1y += safe(r.return1yPct());
                sumScore += r.score().total();
                sixMonth.add(safe(r.return6mPct()));
            }
            int n = analysed.size();
            out.add(new SectorSummary(e.getKey(), all.size(), n, advancing, declining,
                    avg(sum1m, n), avg(sum3m, n), avg(sum6m, n), avg(sum1y, n), median(sixMonth),
                    patterns, avg(sumScore, n)));
        }
        out.sort(Comparator.comparingDouble(SectorSummary::avgReturn6mPct));
        return out;
    }

    private static double safe(double v) {
        return Double.isNaN(v) ? 0 : v;
    }

    private static double avg(double sum, int n) {
        return n == 0 ? Double.NaN : Math.round(sum / n * 100.0) / 100.0;
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int mid = sorted.size() / 2;
        double m = sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
        return Math.round(m * 100.0) / 100.0;
    }
}
