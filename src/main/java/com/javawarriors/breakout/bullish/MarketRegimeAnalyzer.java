package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.model.Bar;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Classifies the market itself before any individual stock is judged.
 *
 * <p>The reason this runs first: the same stock setup is worth very different amounts in different
 * markets. A textbook breakout in a market where the Nifty 500 is under a falling 50 EMA fails far
 * more often than the identical chart in a rising market, and a ranking engine that ignores that
 * hands the user its most confident-looking signals at exactly the worst moment. So the regime does
 * two things downstream: it scales every score slightly, and in a BEARISH regime it withholds
 * BUY NOW outright - the setups are still ranked and still visible, they just are not called entries.
 *
 * <p>Both indices are read because they answer different questions. The Nifty 50 is where the
 * index-level money is; the Nifty 500 is where the breadth is. A market carried by ten large caps
 * scores well on one and poorly on the other, and that disagreement is worth seeing.
 */
public final class MarketRegimeAnalyzer {

    /** Points available per index: price&gt;EMA20, price&gt;EMA50, EMA20&gt;EMA50, EMA50&gt;EMA200, HH+HL. */
    private static final int POINTS_PER_INDEX = 5;

    public record IndexState(String name, boolean known, double price, double ema20, double ema50,
                             double ema200, double return1mPct, double return3mPct,
                             String structure, int score) {

        public static IndexState unknown(String name) {
            return new IndexState(name, false, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                    Double.NaN, Double.NaN, "UNKNOWN", 0);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("known", known);
            if (known) {
                row.put("price", price);
                row.put("ema20", ema20);
                row.put("ema50", ema50);
                row.put("ema200", ema200);
                row.put("return1mPct", return1mPct);
                row.put("return3mPct", return3mPct);
                row.put("structure", structure);
                row.put("score", score);
                row.put("maxScore", POINTS_PER_INDEX);
            }
            return row;
        }
    }

    /**
     * @param scoreMultiplier applied to every stock's final score. Deliberately gentle - the regime
     *                        tilts the ranking, while {@code allowsBuyNow} is what actually stops a
     *                        weak market producing entry calls.
     */
    public record Regime(String regime, int score, int maxScore, double scoreMultiplier,
                         boolean allowsBuyNow, String summary, IndexState nifty50, IndexState nifty500) {

        public boolean isBullish() {
            return "BULLISH".equals(regime);
        }

        public boolean isBearish() {
            return "BEARISH".equals(regime);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("regime", regime);
            row.put("score", score);
            row.put("maxScore", maxScore);
            row.put("scoreMultiplier", scoreMultiplier);
            row.put("allowsBuyNow", allowsBuyNow);
            row.put("summary", summary);
            row.put("nifty50", nifty50.toRow());
            row.put("nifty500", nifty500.toRow());
            return row;
        }
    }

    private MarketRegimeAnalyzer() {
    }

    public static Regime analyze(List<Bar> nifty50Bars, List<Bar> nifty500Bars, BullishConfig cfg) {
        IndexState fifty = state("Nifty 50", nifty50Bars);
        IndexState fiveHundred = state("Nifty 500", nifty500Bars);

        int known = (fifty.known() ? 1 : 0) + (fiveHundred.known() ? 1 : 0);
        if (known == 0) {
            // No index data at all. NEUTRAL rather than BULLISH: an unknown market must never be
            // the thing that unlocks a BUY NOW.
            return new Regime("NEUTRAL", 0, 2 * POINTS_PER_INDEX, 0.97, true,
                    "No index data available - treating the market as neutral.",
                    fifty, fiveHundred);
        }

        int raw = fifty.score() + fiveHundred.score();
        int maxRaw = known * POINTS_PER_INDEX;
        // Normalise to the two-index scale so the thresholds mean the same thing when one index
        // failed to fetch.
        int score = (int) Math.round((double) raw / maxRaw * (2 * POINTS_PER_INDEX));
        int maxScore = 2 * POINTS_PER_INDEX;

        String regime;
        if (score >= cfg.getRegimeBullishScore()) regime = "BULLISH";
        else if (score < cfg.getRegimeBearishScore()) regime = "BEARISH";
        else regime = "NEUTRAL";

        double multiplier = switch (regime) {
            case "BULLISH" -> 1.0;
            case "BEARISH" -> 0.90;
            default -> 0.97;
        };

        return new Regime(regime, score, maxScore, multiplier, !"BEARISH".equals(regime),
                summarise(regime, fifty, fiveHundred), fifty, fiveHundred);
    }

    private static String summarise(String regime, IndexState fifty, IndexState fiveHundred) {
        StringBuilder sb = new StringBuilder();
        sb.append(switch (regime) {
            case "BULLISH" -> "Broad market is trending up";
            case "BEARISH" -> "Broad market is weak - long setups are lower probability here";
            default -> "Broad market is mixed";
        });
        IndexState lead = fiveHundred.known() ? fiveHundred : fifty;
        if (lead.known()) {
            sb.append(": ").append(lead.name()).append(" ")
              .append(lead.price() > lead.ema50() ? "above" : "below").append(" its 50 EMA, ")
              .append(lead.ema50() > lead.ema200() ? "50 EMA above 200 EMA" : "50 EMA below 200 EMA")
              .append(", structure ").append(lead.structure()).append(".");
        }
        if (fifty.known() && fiveHundred.known() && fifty.score() != fiveHundred.score()) {
            sb.append(fifty.score() > fiveHundred.score()
                    ? " Large caps are leading the broader market."
                    : " Breadth is better than the headline index.");
        }
        return sb.toString();
    }

    private static IndexState state(String name, List<Bar> bars) {
        if (bars == null || bars.size() < 60) return IndexState.unknown(name);

        IndicatorSnapshot s = IndicatorSnapshot.of(name, bars);
        String structure = PriceStructure.classify(s);

        int score = 0;
        if (s.price > s.lastEma20) score++;
        if (s.price > s.lastEma50) score++;
        if (s.lastEma20 > s.lastEma50) score++;
        if (s.lastEma50 > s.lastEma200) score++;
        if ("HH+HL".equals(structure)) score++;

        return new IndexState(name, true, s.price, s.lastEma20, s.lastEma50, s.lastEma200,
                Double.isNaN(s.return1m) ? Double.NaN : s.return1m * 100,
                Double.isNaN(s.return3m) ? Double.NaN : s.return3m * 100,
                structure, score);
    }
}
