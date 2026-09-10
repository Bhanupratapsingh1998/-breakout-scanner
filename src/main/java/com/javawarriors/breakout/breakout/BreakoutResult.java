package com.javawarriors.breakout.breakout;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds the outcome of the breakout checklist plus the numbers behind it.
 *
 * Two-tier verdict: a fixed set of checks are HARD GATES — if any fails, the stock is
 * REJECTED outright regardless of how well it scores elsewhere. Stocks that clear every
 * gate are then ranked by their SOFT score (the remaining checks) into STRONG/GOOD/WATCH.
 */
public class BreakoutResult {

    public final String symbol;
    public final Map<String, Boolean> checks = new LinkedHashMap<>();
    public final Map<String, Integer> weights = new LinkedHashMap<>();
    public final Map<String, Boolean> gates = new LinkedHashMap<>();
    public final Map<String, Double> values = new LinkedHashMap<>();

    /**
     * Entry-timing dimension, separate from the gate/soft checklist above: SETUP QUALITY asks
     * "is this stock worth trading," ENTRY QUALITY asks "is now the right time to buy it."
     * A stock can be a 10/10 setup and still be a 2/10 entry — see `classification`.
     */
    public int setupScore;
    public final int setupMax = 10;
    public int entryScore;
    public final int entryMax = 10;
    public String structure = "MIXED";            // HH+HL / LH+LL / MIXED
    public String exhaustionRisk = "LOW";         // LOW / MEDIUM / HIGH profit-booking risk
    public String classification = "REJECTED";    // BUY NOW / WAIT FOR PULLBACK / WAIT FOR BREAKOUT/RETEST / AVOID CHASING / REJECTED
    public String breakoutStatus = "NOT_CONFIRMED"; // CONFIRMED_HOLDING / CONFIRMED_RETESTING / FAILED / NOT_CONFIRMED

    /** Structure of the breakout candle itself. Reported, but not yet gating anything. */
    public BreakoutBarQuality barQuality = BreakoutBarQuality.unknown();

    /** How far price has run from its own mean. A CHASE rating withholds BUY NOW. */
    public StretchFromMean stretch = StretchFromMean.unknown();

    public BreakoutResult(String symbol) {
        this.symbol = symbol;
    }

    public void addCheck(String label, boolean pass, int weight, boolean isHardGate) {
        checks.put(label, pass);
        weights.put(label, weight);
        gates.put(label, isHardGate);
    }

    /** Weighted points earned across ALL checks (hard + soft) — a general-purpose overview score. */
    public int score() {
        int sum = 0;
        for (Map.Entry<String, Boolean> e : checks.entrySet()) {
            if (e.getValue()) sum += weights.getOrDefault(e.getKey(), 1);
        }
        return sum;
    }

    /** Total points available across all checks. */
    public int maxScore() {
        return weights.values().stream().mapToInt(Integer::intValue).sum();
    }

    public boolean passedAllGates() {
        for (Map.Entry<String, Boolean> e : gates.entrySet()) {
            if (e.getValue() && !checks.get(e.getKey())) return false;
        }
        return true;
    }

    public List<String> failedGates() {
        List<String> failed = new ArrayList<>();
        for (Map.Entry<String, Boolean> e : gates.entrySet()) {
            if (e.getValue() && !checks.get(e.getKey())) failed.add(e.getKey());
        }
        return failed;
    }

    /** Points earned from soft (non-gate) checks only. */
    public int softScore() {
        int sum = 0;
        for (Map.Entry<String, Boolean> e : checks.entrySet()) {
            if (!gates.get(e.getKey()) && e.getValue()) sum += weights.getOrDefault(e.getKey(), 1);
        }
        return sum;
    }

    /** Points available from soft (non-gate) checks only. */
    public int softMaxScore() {
        int sum = 0;
        for (Map.Entry<String, Integer> e : weights.entrySet()) {
            if (!gates.get(e.getKey())) sum += e.getValue();
        }
        return sum;
    }

    /** STRONG / GOOD / WATCH (gates all passed, ranked by soft score) or REJECTED (a gate failed). */
    public String recommendation() {
        if (!passedAllGates()) return "REJECTED";
        int s = softScore();
        int max = softMaxScore();
        if (s == max) return "STRONG";
        if (s == max - 1) return "GOOD";
        return "WATCH";
    }

    /** Rank for sorting: higher is better. Ties within a tier break by overall score(). */
    public int tierRank() {
        return switch (recommendation()) {
            case "STRONG" -> 3;
            case "GOOD" -> 2;
            case "WATCH" -> 1;
            default -> 0; // REJECTED
        };
    }

    public String verdict() {
        if (!passedAllGates()) return "REJECTED — failed: " + String.join(", ", failedGates());
        int s = softScore();
        int max = softMaxScore();
        String tier = recommendation();
        return switch (tier) {
            case "STRONG" -> "STRONG — all gates passed, soft score " + s + "/" + max;
            case "GOOD" -> "GOOD — all gates passed, soft score " + s + "/" + max;
            default -> "WATCH — all gates passed, soft score " + s + "/" + max;
        };
    }

    /**
     * The single JSON-shaped row builder for the dashboard API — used by both the full-scan
     * payload (GET /api/results) and the single-symbol lookup (GET /api/analyze) so the two
     * paths can never drift out of sync on field names.
     */
    public Map<String, Object> toRow(String name, String universe) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("symbol", symbol);
        row.put("name", name);
        row.put("universe", universe);
        row.put("score", score());
        row.put("total", maxScore());
        row.put("rating", recommendation());
        row.put("verdict", verdict());
        row.put("passedAllGates", passedAllGates());
        row.put("softScore", softScore());
        row.put("softTotal", softMaxScore());
        row.put("setupScore", setupScore);
        row.put("setupTotal", setupMax);
        row.put("entryScore", entryScore);
        row.put("entryTotal", entryMax);
        row.put("structure", structure);
        row.put("exhaustionRisk", exhaustionRisk);
        row.put("classification", classification);
        row.put("breakoutStatus", breakoutStatus);
        row.put("breakoutBarQuality", barQuality.toRow());
        row.put("stretchFromMean", stretch.toRow());
        row.put("values", values);

        List<Map<String, Object>> checkRows = new ArrayList<>();
        checks.forEach((label, pass) -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("label", label);
            c.put("pass", pass);
            c.put("weight", weights.getOrDefault(label, 1));
            c.put("gate", gates.getOrDefault(label, false));
            checkRows.add(c);
        });
        row.put("checks", checkRows);
        return row;
    }
}
