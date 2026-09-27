package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.intraday.CandlePatternDetector;
import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The W (and its mirror, the M) drawn across a group of candles.
 *
 * <h2>Why this cannot be a merged-candle shape</h2>
 *
 * <p>Every other pattern in this package is read off the merged candle. This one cannot be, and the
 * reason is worth stating because it is the whole justification for a second detector: merging
 * keeps only the <em>lowest</em> low of the group. A level tested twice with a bounce between and a
 * level touched once produce the identical merged candle - one long tail. The second touch, which
 * is the entire point of a W, is destroyed by the operation that makes every other pattern here
 * visible. So this reads the base candles directly.
 *
 * <p>What it looks for, over a group of three or more candles:
 *
 * <ol>
 *   <li><b>Two feet.</b> Two candles bottom within {@code doubleLevelTolerance} of each other, and
 *       they are not adjacent - there is at least one candle between them.</li>
 *   <li><b>A bounce.</b> The candles between them lift meaningfully off that level, measured
 *       against the group's own range so the test means the same on any timeframe. Two lows with
 *       nothing in between is a flat base, not a W.</li>
 *   <li><b>A close off the floor.</b> The group finishes in its upper reaches - the second foot
 *       held and price left it behind.</li>
 *   <li><b>Context.</b> Price was falling into the group, the same test the wick detector applies
 *       and for the same reason.</li>
 * </ol>
 *
 * <p>The bearish mirror is an M: two matching highs, a dip between, a close near the low. Every
 * test below swaps ends rather than being written twice.
 */
@Component
public class DoubleBottomDetector {

    /** The newest W or M for every enabled direction and group size. */
    public List<WickSignal> detectAll(String symbol, String companyName, String sector,
                                      String interval, List<Bar> base, WickReversalConfig cfg) {
        List<WickSignal> out = new ArrayList<>();
        if (!cfg.isDoubleEnabled()) return out;
        for (Direction direction : cfg.enabledDirections()) {
            for (int count : cfg.getCandleCounts()) {
                // Two feet plus a bounce needs at least three candles; a pair cannot hold a W.
                if (count < 3) continue;
                WickSignal signal =
                        detect(symbol, companyName, sector, interval, base, count, direction, cfg);
                if (signal != null) out.add(signal);
            }
        }
        return out;
    }

    /** The newest qualifying group of one direction and size, or null. Pure - no network, no state. */
    public WickSignal detect(String symbol, String companyName, String sector, String interval,
                             List<Bar> base, int count, Direction direction,
                             WickReversalConfig cfg) {
        if (base == null || count < 3 || base.size() < cfg.getMinBars()) return null;

        List<Bar> merged = CandleMerger.rolling(base, count);
        int last = base.size() - 1;
        int oldest = Math.max(count - 1, last - cfg.getLookbackBars() + 1);

        for (int i = last; i >= oldest; i--) {
            WickSignal signal =
                    at(symbol, companyName, sector, interval, base, merged, i, count, direction, cfg);
            if (signal != null) return signal;
        }
        return null;
    }

    /** Tests the group ending at base index {@code i}. Package-private so tests can aim at one. */
    WickSignal at(String symbol, String companyName, String sector, String interval,
                  List<Bar> base, List<Bar> merged, int i, int count, Direction direction,
                  WickReversalConfig cfg) {
        int start = i - count + 1;
        if (start < 0) return null;
        boolean bullish = direction == Direction.BULLISH;

        Bar m = merged.get(i);
        double range = CandleMerger.range(m);
        if (!(range > 0)) return null;

        Feet feet = findFeet(base, start, i, bullish, cfg.getDoubleLevelTolerance());
        if (feet == null) return null;

        // (2) A bounce between the feet, measured in the group's own range.
        double bounce = bullish
                ? (feet.peakBetween() - feet.level()) / range
                : (feet.level() - feet.peakBetween()) / range;
        if (!(bounce >= cfg.getDoubleMinBounce())) return null;

        // (3) The group finished away from the level it tested.
        double closePosition = bullish
                ? (m.close() - m.low()) / range
                : (m.high() - m.close()) / range;
        if (!(closePosition >= cfg.getMinClosePosition())) return null;

        // (4) Context: the same question the wick detector asks, for the same reason.
        if (!movedIntoTheGroup(base, i, count, bullish, cfg)) return null;

        Bar first = base.get(start);
        Bar lastBar = base.get(i);
        double priorMove = priorMoveInRanges(base, start, m, bullish, cfg);
        double fromExtreme = distanceFromExtremeInRanges(base, i, m, bullish, cfg);
        double volumeRatio = volumeRatio(merged, i);

        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("base", basePoints(feet.mismatch(), bounce));
        parts.put("recovery", WickReversalDetector.recoveryPoints(closePosition));
        parts.put("priorMove", WickReversalDetector.priorMovePoints(priorMove));
        parts.put("atExtreme", WickReversalDetector.extremePoints(fromExtreme));
        parts.put("volume", WickReversalDetector.volumePoints(volumeRatio));
        double total = 0;
        for (double v : parts.values()) total += v;

        double price = base.get(base.size() - 1).close();
        String[] status = WickReversalDetector.statusOf(base, i, m, bullish);
        double trigger = bullish ? m.high() : m.low();
        // The level that would prove it wrong is the shelf the two feet stand on, not the extreme
        // of the group - a close through the tested level is what breaks a double bottom.
        double invalidation = feet.level();
        double riskPct = price > 0 ? Math.abs(price - invalidation) / price * 100 : Double.NaN;

        return new WickSignal(symbol, companyName, sector,
                interval, count, WickReversalConfig.mergedLabel(interval, count),
                bullish ? WickSignal.BULLISH : WickSignal.BEARISH,
                bullish ? WickSignal.DOUBLE_BOTTOM : WickSignal.DOUBLE_TOP,
                lastBar.time(), base.size() - 1 - i,
                first.open(), first.high(), first.low(), first.close(),
                lastBar.open(), lastBar.high(), lastBar.low(), lastBar.close(),
                m.open(), m.high(), m.low(), m.close(),
                round(CandlePatternDetector.body(m)),
                round(CandlePatternDetector.lowerWick(m)),
                round(CandlePatternDetector.upperWick(m)),
                round(feet.mismatch() * 100),
                round(closePosition * 100) / 100.0, round(bounce * 100),
                round(priorMove), round(fromExtreme), round(volumeRatio),
                price, trigger, invalidation, round(riskPct),
                status[0], reason(status[0], bullish, feet, status[1]),
                round(total), parts);
    }

    /** The two matching extremes and the turn between them. */
    private record Feet(int firstIndex, int secondIndex, double level, double peakBetween,
                        double mismatch) {
    }

    /**
     * The best pair of non-adjacent candles bottoming (or topping) at the same level.
     *
     * <p>Best means closest matched, not deepest: a W is defined by the two feet agreeing, and
     * picking the lowest pair would prefer a ragged descent over a level shelf.
     */
    private static Feet findFeet(List<Bar> base, int start, int end, boolean bullish,
                                 double tolerance) {
        Feet best = null;
        for (int a = start; a <= end - 2; a++) {
            for (int b = a + 2; b <= end; b++) {
                double ea = bullish ? base.get(a).low() : base.get(a).high();
                double eb = bullish ? base.get(b).low() : base.get(b).high();
                if (!(ea > 0) || !(eb > 0)) continue;

                double mismatch = Math.abs(ea - eb) / Math.max(ea, eb);
                if (mismatch > tolerance) continue;

                double level = bullish ? Math.min(ea, eb) : Math.max(ea, eb);
                double peak = bullish ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
                for (int k = a + 1; k < b; k++) {
                    peak = bullish ? Math.max(peak, base.get(k).high())
                                   : Math.min(peak, base.get(k).low());
                }
                if (best == null || mismatch < best.mismatch()) {
                    best = new Feet(a, b, level, peak, mismatch);
                }
            }
        }
        return best;
    }

    private static String reason(String status, boolean bullish, Feet feet, String fallback) {
        String shape = bullish ? "low" : "high";
        String base = String.format(
                "The same %s was tested twice, %d candles apart and within %.1f%% of itself. ",
                shape, feet.secondIndex() - feet.firstIndex(), feet.mismatch() * 100);
        return base + fallback;
    }

    /**
     * 30 - how convincing the base is.
     *
     * <p>Two things make a W rather than a coincidence: the feet agreeing, and a real turn between
     * them. Scoring them together stops a pair of near-identical lows with a flat middle from
     * reading as strongly as a genuine double bottom.
     */
    static double basePoints(double mismatch, double bounce) {
        double matchPoints = mismatch <= 0.002 ? 18 : mismatch <= 0.005 ? 14 : mismatch <= 0.01 ? 10 : 6;
        double bouncePoints = bounce >= 0.5 ? 12 : bounce >= 0.35 ? 9 : bounce >= 0.2 ? 6 : 3;
        return matchPoints + bouncePoints;
    }

    // The context and setting helpers below are the wick detector's, reused rather than re-derived
    // so that "was it falling into this" means one thing across the package.

    private static boolean movedIntoTheGroup(List<Bar> base, int i, int count, boolean bullish,
                                             WickReversalConfig cfg) {
        int before = i - count;
        int from = before - cfg.getContextBars();
        if (from < 0) return false;
        double then = base.get(from).close();
        double now = base.get(before).close();
        return bullish ? now < then : now > then;
    }

    private static double priorMoveInRanges(List<Bar> base, int start, Bar m, boolean bullish,
                                            WickReversalConfig cfg) {
        int from = Math.max(0, start - cfg.getContextBars());
        double range = CandleMerger.range(m);
        if (!(range > 0)) return 0;
        if (bullish) {
            double high = 0;
            for (int k = from; k <= start; k++) high = Math.max(high, base.get(k).high());
            return (high - m.low()) / range;
        }
        double low = Double.MAX_VALUE;
        for (int k = from; k <= start; k++) low = Math.min(low, base.get(k).low());
        return (m.high() - low) / range;
    }

    private static double distanceFromExtremeInRanges(List<Bar> base, int i, Bar m, boolean bullish,
                                                      WickReversalConfig cfg) {
        int from = Math.max(0, i - cfg.getSwingLookback());
        double range = CandleMerger.range(m);
        if (!(range > 0)) return 0;
        if (bullish) {
            double low = Double.MAX_VALUE;
            for (int k = from; k <= i; k++) low = Math.min(low, base.get(k).low());
            return (m.low() - low) / range;
        }
        double high = 0;
        for (int k = from; k <= i; k++) high = Math.max(high, base.get(k).high());
        return (high - m.high()) / range;
    }

    private static double volumeRatio(List<Bar> merged, int i) {
        int from = Math.max(1, i - 20);
        if (from >= i) return 1;
        double sum = 0;
        for (int k = from; k < i; k++) sum += merged.get(k).volume();
        double avg = sum / (i - from);
        return avg > 0 ? merged.get(i).volume() / avg : 1;
    }

    private static double round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 100) / 100.0;
    }
}
