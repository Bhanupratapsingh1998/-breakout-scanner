package com.javawarriors.breakout.intraday;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full single- and multi-candle pattern vocabulary, in both directions and without scoring.
 *
 * <p>Written for the intraday scanner, which has since been removed. It survives that removal
 * because the Index 500 analysis depends on it: {@code index500.pattern.CandlePatternAdapter}
 * wraps {@link #hammer} and {@link #bullishEngulfing} as two of its fifteen detectors. It is kept
 * in this package rather than moved so that the removal touched no working feature — the package
 * name is now historical, not a statement about where this can be used.
 *
 * <p>Deliberately separate from {@code candlestick.CandlestickPatternAnalyzer}, which detects three
 * bullish patterns and is wired into the daily reversal scan's 0-10 scoring. This one needs the
 * whole vocabulary in both directions and no scoring, and changing the existing analyzer to serve
 * both would alter what the daily reversal scan reports. Two small focused detectors beat one that
 * quietly changes an existing feature's output.
 *
 * <p>Two pairs of patterns are the same <em>shape</em> and differ only in context: a long lower
 * wick is a Hammer after a decline and a Hanging Man after a rally; a long upper wick is an
 * Inverted Hammer after a decline and a Shooting Star after a rally. That context test is what
 * makes the classification objective rather than a matter of what the chart "looks like", so it is
 * applied rather than assumed - see {@link #priorTrendUp}.
 */
public final class CandlePatternDetector {

    /** Bars of context used to decide whether a wick pattern sits after a rally or a decline. */
    private static final int CONTEXT_BARS = 5;

    public enum Direction { BULLISH, BEARISH }

    /**
     * @param strength 1-3; higher means the shape met the stricter form of its own definition
     * @param index    the bar the pattern completes on
     */
    public record CandlePattern(String type, String name, Direction direction, int strength,
                                int index, String description) {

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("name", name);
            row.put("direction", direction.name());
            row.put("strength", strength);
            row.put("description", description);
            return row;
        }
    }

    /** Every pattern this detector knows, for the dashboard's filter dropdown. */
    public static final Map<String, String> BULLISH_TYPES = Map.ofEntries(
            Map.entry("HAMMER", "Hammer"),
            Map.entry("INVERTED_HAMMER", "Inverted Hammer"),
            Map.entry("BULLISH_ENGULFING", "Bullish Engulfing"),
            Map.entry("MORNING_STAR", "Morning Star"),
            Map.entry("PIERCING_LINE", "Piercing Line"),
            Map.entry("BULLISH_HARAMI", "Bullish Harami"),
            Map.entry("DRAGONFLY_DOJI", "Dragonfly Doji"),
            Map.entry("THREE_WHITE_SOLDIERS", "Three White Soldiers"),
            Map.entry("BULLISH_MARUBOZU", "Bullish Marubozu"));

    public static final Map<String, String> BEARISH_TYPES = Map.ofEntries(
            Map.entry("SHOOTING_STAR", "Shooting Star"),
            Map.entry("HANGING_MAN", "Hanging Man"),
            Map.entry("BEARISH_ENGULFING", "Bearish Engulfing"),
            Map.entry("EVENING_STAR", "Evening Star"),
            Map.entry("DARK_CLOUD_COVER", "Dark Cloud Cover"),
            Map.entry("BEARISH_HARAMI", "Bearish Harami"),
            Map.entry("GRAVESTONE_DOJI", "Gravestone Doji"),
            Map.entry("THREE_BLACK_CROWS", "Three Black Crows"),
            Map.entry("BEARISH_MARUBOZU", "Bearish Marubozu"));

    private CandlePatternDetector() {
    }

    /** Every pattern completing on the final bar of {@code bars}, in either direction. */
    public static List<CandlePattern> detectAt(List<Bar> bars, int i) {
        List<CandlePattern> out = new ArrayList<>();
        if (bars == null || i < 2 || i >= bars.size()) return out;

        addBullish(out, bars, i);
        addBearish(out, bars, i);
        return out;
    }

    /** The strongest pattern of the given direction on bar {@code i}, or null. */
    public static CandlePattern strongest(List<Bar> bars, int i, Direction direction) {
        return detectAt(bars, i).stream()
                .filter(p -> p.direction() == direction)
                .max((a, b) -> Integer.compare(a.strength(), b.strength()))
                .orElse(null);
    }

    // ------------------------------------------------------------------ bullish

    private static void addBullish(List<CandlePattern> out, List<Bar> bars, int i) {
        Bar c = bars.get(i);
        Bar p = bars.get(i - 1);
        double range = range(c), body = body(c);
        boolean downContext = !priorTrendUp(bars, i);

        // Long lower wick. Bullish only when it follows a decline - otherwise it is a Hanging Man.
        if (range > 0 && body > 0 && lowerWick(c) >= 2 * body && upperWick(c) <= 0.15 * range
                && body <= 0.35 * range && downContext) {
            out.add(new CandlePattern("HAMMER", "Hammer", Direction.BULLISH,
                    lowerWick(c) >= 3 * body ? 3 : 2, i,
                    "Long lower wick after a decline - sellers pushed price down and lost it back."));
        }
        if (range > 0 && body > 0 && upperWick(c) >= 2 * body && lowerWick(c) <= 0.15 * range
                && body <= 0.35 * range && downContext) {
            out.add(new CandlePattern("INVERTED_HAMMER", "Inverted Hammer", Direction.BULLISH, 2, i,
                    "Long upper wick after a decline - a first attempt to bid price back up."));
        }
        if (range > 0 && body <= 0.05 * range && lowerWick(c) >= 0.6 * range && upperWick(c) <= 0.1 * range) {
            out.add(new CandlePattern("DRAGONFLY_DOJI", "Dragonfly Doji", Direction.BULLISH, 2, i,
                    "Open and close at the high with a long lower wick - the whole decline was rejected."));
        }
        if (isBull(c) && range > 0 && body >= 0.9 * range) {
            out.add(new CandlePattern("BULLISH_MARUBOZU", "Bullish Marubozu", Direction.BULLISH, 2, i,
                    "An almost all-body up candle - buyers held control from open to close."));
        }
        if (isBull(c) && isBear(p) && c.open() <= p.close() && c.close() >= p.open() && body > body(p)) {
            out.add(new CandlePattern("BULLISH_ENGULFING", "Bullish Engulfing", Direction.BULLISH,
                    body >= 1.5 * body(p) ? 3 : 2, i,
                    "An up candle that swallows the previous down candle whole."));
        }
        if (isBull(c) && isBear(p) && body(p) > 0 && c.open() < p.close()
                && c.close() > p.close() + 0.5 * body(p) && c.close() < p.open()) {
            out.add(new CandlePattern("PIERCING_LINE", "Piercing Line", Direction.BULLISH, 2, i,
                    "Opened below the prior close and recovered past its midpoint."));
        }
        if (isBear(p) && body(p) > 0 && body <= 0.6 * body(p)
                && Math.max(c.open(), c.close()) <= p.open() && Math.min(c.open(), c.close()) >= p.close()) {
            out.add(new CandlePattern("BULLISH_HARAMI", "Bullish Harami", Direction.BULLISH, 1, i,
                    "A small candle held inside the prior down candle - the decline has stalled."));
        }
        if (i >= 2) {
            Bar a = bars.get(i - 2);
            if (isBear(a) && body(a) > 0 && body(p) <= 0.5 * body(a)
                    && isBull(c) && c.close() > a.close() + 0.5 * body(a) && c.close() < a.open()) {
                out.add(new CandlePattern("MORNING_STAR", "Morning Star", Direction.BULLISH, 3, i,
                        "Down candle, a pause, then an up candle back through its midpoint."));
            }
            if (isBull(a) && isBull(p) && isBull(c)
                    && p.close() > a.close() && c.close() > p.close()
                    && body(a) > 0 && body(p) > 0 && body > 0
                    && upperWick(p) <= body(p) && upperWick(c) <= body) {
                out.add(new CandlePattern("THREE_WHITE_SOLDIERS", "Three White Soldiers", Direction.BULLISH, 3, i,
                        "Three rising up candles, each closing near its high."));
            }
        }
    }

    // ------------------------------------------------------------------ bearish

    private static void addBearish(List<CandlePattern> out, List<Bar> bars, int i) {
        Bar c = bars.get(i);
        Bar p = bars.get(i - 1);
        double range = range(c), body = body(c);
        boolean upContext = priorTrendUp(bars, i);

        // The strategy's headline pattern: a long upper wick rejecting a rally.
        if (range > 0 && body > 0 && upperWick(c) >= 2 * body && lowerWick(c) <= 0.15 * range
                && body <= 0.35 * range && upContext) {
            out.add(new CandlePattern("SHOOTING_STAR", "Shooting Star", Direction.BEARISH,
                    upperWick(c) >= 3 * body ? 3 : 2, i,
                    "Long upper wick after a rally - buyers pushed price up and lost all of it."));
        }
        if (range > 0 && body > 0 && lowerWick(c) >= 2 * body && upperWick(c) <= 0.15 * range
                && body <= 0.35 * range && upContext) {
            out.add(new CandlePattern("HANGING_MAN", "Hanging Man", Direction.BEARISH, 2, i,
                    "Long lower wick after a rally - selling is appearing under an advance."));
        }
        if (range > 0 && body <= 0.05 * range && upperWick(c) >= 0.6 * range && lowerWick(c) <= 0.1 * range) {
            out.add(new CandlePattern("GRAVESTONE_DOJI", "Gravestone Doji", Direction.BEARISH, 2, i,
                    "Open and close at the low with a long upper wick - the whole rally was rejected."));
        }
        if (isBear(c) && range > 0 && body >= 0.9 * range) {
            out.add(new CandlePattern("BEARISH_MARUBOZU", "Bearish Marubozu", Direction.BEARISH, 2, i,
                    "An almost all-body down candle - sellers held control from open to close."));
        }
        if (isBear(c) && isBull(p) && c.open() >= p.close() && c.close() <= p.open() && body > body(p)) {
            out.add(new CandlePattern("BEARISH_ENGULFING", "Bearish Engulfing", Direction.BEARISH,
                    body >= 1.5 * body(p) ? 3 : 2, i,
                    "A down candle that swallows the previous up candle whole."));
        }
        if (isBear(c) && isBull(p) && body(p) > 0 && c.open() > p.close()
                && c.close() < p.close() - 0.5 * body(p) && c.close() > p.open()) {
            out.add(new CandlePattern("DARK_CLOUD_COVER", "Dark Cloud Cover", Direction.BEARISH, 2, i,
                    "Opened above the prior close and gave back more than half of it."));
        }
        if (isBull(p) && body(p) > 0 && body <= 0.6 * body(p)
                && Math.max(c.open(), c.close()) <= p.close() && Math.min(c.open(), c.close()) >= p.open()) {
            out.add(new CandlePattern("BEARISH_HARAMI", "Bearish Harami", Direction.BEARISH, 1, i,
                    "A small candle held inside the prior up candle - the advance has stalled."));
        }
        if (i >= 2) {
            Bar a = bars.get(i - 2);
            if (isBull(a) && body(a) > 0 && body(p) <= 0.5 * body(a)
                    && isBear(c) && c.close() < a.close() - 0.5 * body(a) && c.close() > a.open()) {
                out.add(new CandlePattern("EVENING_STAR", "Evening Star", Direction.BEARISH, 3, i,
                        "Up candle, a pause, then a down candle back through its midpoint."));
            }
            if (isBear(a) && isBear(p) && isBear(c)
                    && p.close() < a.close() && c.close() < p.close()
                    && body(a) > 0 && body(p) > 0 && body > 0
                    && lowerWick(p) <= body(p) && lowerWick(c) <= body) {
                out.add(new CandlePattern("THREE_BLACK_CROWS", "Three Black Crows", Direction.BEARISH, 3, i,
                        "Three falling down candles, each closing near its low."));
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Whether the {@link #CONTEXT_BARS} before {@code i} were a net advance. This is the test that
     * separates a Hammer from a Hanging Man, and an Inverted Hammer from a Shooting Star.
     */
    static boolean priorTrendUp(List<Bar> bars, int i) {
        int from = Math.max(0, i - CONTEXT_BARS);
        if (from == i) return false;
        return bars.get(i - 1).close() > bars.get(from).close();
    }

    public static double body(Bar b) {
        return Math.abs(b.close() - b.open());
    }

    public static double range(Bar b) {
        return b.high() - b.low();
    }

    public static double upperWick(Bar b) {
        return b.high() - Math.max(b.open(), b.close());
    }

    public static double lowerWick(Bar b) {
        return Math.min(b.open(), b.close()) - b.low();
    }

    public static boolean isBull(Bar b) {
        return b.close() > b.open();
    }

    public static boolean isBear(Bar b) {
        return b.close() < b.open();
    }
}
