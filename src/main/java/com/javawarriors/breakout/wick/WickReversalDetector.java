package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.intraday.CandlePatternDetector;
import com.javawarriors.breakout.intraday.CandlePatternDetector.CandlePattern;
import com.javawarriors.breakout.intraday.CandlePatternDetector.Direction;
import com.javawarriors.breakout.model.Bar;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds the setup in the reference images, and its mirror.
 *
 * <p><b>Bullish.</b> A decisive sell-off taken straight back, which merged into a single bigger
 * candle is a long <em>lower</em> wick: sellers pushed price down and lost it back.
 *
 * <p><b>Bearish.</b> The same thing upside down. A decisive rally sold straight back, merging into
 * a long <em>upper</em> wick: buyers pushed price up and lost it back. Nothing about the reasoning
 * changes - every test below simply swaps which end of the candle it reads, which is why the two
 * directions share one code path rather than getting a copy each.
 *
 * <p>The group is two candles by default - a drop and the recovery, exactly as drawn. Three is the
 * same story told over a slower move. Every enabled direction and group size is detected in one
 * pass over the same bars and tagged with which it is, so choosing between them is a filter rather
 * than another scan.
 *
 * <h2>What makes it a signal rather than a shape</h2>
 *
 * <p>Four things have to hold together, and dropping any one of them lets through a different
 * pattern wearing the same outline:
 *
 * <ol>
 *   <li><b>Composition.</b> The first candle must run <em>with</em> the move and the last one
 *       against it - down then up for bullish, up then down for bearish. Whatever sits between them
 *       is free to be either. A merged candle can grow a long wick out of candles that all point
 *       the same way, and that is not the lost-it-back story the images describe.</li>
 *   <li><b>Shape.</b> The merged candle must be a Hammer or Dragonfly Doji (bullish), or a Shooting
 *       Star or Gravestone Doji (bearish), by {@link CandlePatternDetector}'s existing definitions -
 *       the ones the reversal and Index 500 features already use. That brings the opposite-wick and
 *       body limits with it. Asking the shared detector rather than re-deriving "long wick" here
 *       keeps one definition in the codebase; the config can only tighten it, never loosen it.</li>
 *   <li><b>Follow-through.</b> The merged candle must close far from the extreme it rejected. The
 *       last candle has to actually finish the job, not merely stop the move.</li>
 *   <li><b>Context.</b> Price must already have been moving that way <em>before</em> the group
 *       began - see {@link #movedIntoTheGroup}, and the note there on why the shared detector's own
 *       context test is not sufficient for this particular pattern.</li>
 * </ol>
 *
 * <p>For each direction and group size the most recent qualifying group wins. An older signal is
 * not wrong, but the level it marks has had more time to be taken out, and {@link #statusOf} says
 * whether it has.
 */
@Component
public class WickReversalDetector {

    /**
     * The newest qualifying signal for every enabled direction and group size.
     *
     * <p>A stock can legitimately appear more than once. A rejection that shows at both two and
     * three candles is the same extreme read at two aggregations, and reporting only one of them
     * would hide that the shape survives being looked at differently.
     */
    public List<WickSignal> detectAll(String symbol, String companyName, String sector,
                                      String interval, List<Bar> base, WickReversalConfig cfg) {
        List<WickSignal> out = new ArrayList<>();
        for (Direction direction : cfg.enabledDirections()) {
            for (int count : cfg.getCandleCounts()) {
                WickSignal signal =
                        detect(symbol, companyName, sector, interval, base, count, direction, cfg);
                if (signal != null) out.add(signal);
            }
        }
        return out;
    }

    /**
     * The newest qualifying signal of one direction and group size, or null.
     *
     * <p>Pure: no network, no state. Everything it needs is the bars it is handed, which is what
     * lets the tests drive it with fixtures.
     */
    public WickSignal detect(String symbol, String companyName, String sector, String interval,
                             List<Bar> base, int count, Direction direction,
                             WickReversalConfig cfg) {
        if (base == null || count < 2 || base.size() < cfg.getMinBars()) return null;

        List<Bar> merged = CandleMerger.rolling(base, count);
        int last = base.size() - 1;
        // Never start before index count-1: earlier entries hold fewer candles than they claim.
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
        Bar first = base.get(start);
        Bar last = base.get(i);

        // (1) Composition: one side pushed, the other answered. What happens between them is free.
        boolean composed = bullish
                ? CandlePatternDetector.isBear(first) && CandlePatternDetector.isBull(last)
                : CandlePatternDetector.isBull(first) && CandlePatternDetector.isBear(last);
        if (!composed) return null;

        Bar m = merged.get(i);
        double range = CandlePatternDetector.range(m);
        if (!(range > 0)) return null;

        double body = CandlePatternDetector.body(m);
        double lowerWick = CandlePatternDetector.lowerWick(m);
        double upperWick = CandlePatternDetector.upperWick(m);
        // The wick that did the rejecting: below for bullish, above for bearish.
        double rejectionWick = bullish ? lowerWick : upperWick;

        // A zero body is a perfect rejection, not a division by zero.
        double wickToBody = body > 0 ? rejectionWick / body : Double.POSITIVE_INFINITY;
        if (!(wickToBody >= cfg.getMinWickToBody())) return null;

        // (3) Follow-through: how far the close finished from the extreme it rejected.
        double closePosition = bullish
                ? (m.close() - m.low()) / range
                : (m.high() - m.close()) / range;
        if (!(closePosition >= cfg.getMinClosePosition())) return null;

        // (2) Shape, per the shared detector.
        if (!hasRejectionShape(merged, i, bullish)) return null;

        // (4) Context, measured from before the move rather than across it.
        if (!movedIntoTheGroup(base, i, count, bullish, cfg)) return null;

        double priorMove = priorMoveInRanges(base, start, m, bullish, cfg);
        double fromExtreme = distanceFromExtremeInRanges(base, i, m, bullish, cfg);
        double volumeRatio = volumeRatio(merged, i);
        double recoveredPct = recoveredPct(first, last, m, bullish);

        Map<String, Double> parts = new LinkedHashMap<>();
        parts.put("wick", wickPoints(wickToBody));
        parts.put("recovery", recoveryPoints(closePosition));
        parts.put("priorMove", priorMovePoints(priorMove));
        parts.put("atExtreme", extremePoints(fromExtreme));
        parts.put("volume", volumePoints(volumeRatio));
        double total = 0;
        for (double v : parts.values()) total += v;
        double score = round(total);

        double price = base.get(base.size() - 1).close();
        String[] status = statusOf(base, i, m, bullish);
        // Breaking the far side proves the rejection right; losing the rejected extreme proves it wrong.
        double trigger = bullish ? m.high() : m.low();
        double invalidation = bullish ? m.low() : m.high();
        double riskPct = price > 0 ? Math.abs(price - invalidation) / price * 100 : Double.NaN;

        return new WickSignal(symbol, companyName, sector,
                interval, count, WickReversalConfig.mergedLabel(interval, count),
                bullish ? WickSignal.BULLISH : WickSignal.BEARISH,
                last.time(), base.size() - 1 - i,
                first.open(), first.high(), first.low(), first.close(),
                last.open(), last.high(), last.low(), last.close(),
                m.open(), m.high(), m.low(), m.close(),
                round(body), round(lowerWick), round(upperWick),
                Double.isInfinite(wickToBody) ? 99 : round(wickToBody),
                round(closePosition * 100) / 100.0, round(recoveredPct),
                round(priorMove), round(fromExtreme), round(volumeRatio),
                price, trigger, invalidation, round(riskPct),
                status[0], status[1], score, parts);
    }

    /**
     * Was price already moving that way before the group began?
     *
     * <p>The shared detector carries a context test of its own, and for a single candle it is the
     * right one. It is not enough here. It measures from the candle immediately before the pattern -
     * but for this pattern that candle <em>is</em> the move, and a 4% candle swamps a five-bar
     * comparison of closes. The effect is that a sharp drop in the middle of a steady uptrend reads
     * as "a decline", and the scan would report every bought-up dip in a rising stock as a reversal.
     *
     * <p>So the trend is measured from the last candle the setup has not touched, over a longer
     * window. That asks the question the reference images actually pose: did this reject the end of
     * a move, or interrupt one going the other way? Too little history to tell is answered with
     * "no" rather than a guess.
     */
    private static boolean movedIntoTheGroup(List<Bar> base, int i, int count, boolean bullish,
                                             WickReversalConfig cfg) {
        int before = i - count;
        int from = before - cfg.getContextBars();
        if (from < 0) return false;
        double then = base.get(from).close();
        double now = base.get(before).close();
        return bullish ? now < then : now > then;
    }

    /** True when the shared detector calls the merged candle the right kind of rejection. */
    private static boolean hasRejectionShape(List<Bar> merged, int i, boolean bullish) {
        for (CandlePattern p : CandlePatternDetector.detectAt(merged, i)) {
            String t = p.type();
            boolean match = bullish
                    ? "HAMMER".equals(t) || "DRAGONFLY_DOJI".equals(t)
                    : "SHOOTING_STAR".equals(t) || "GRAVESTONE_DOJI".equals(t);
            if (match) return true;
        }
        return false;
    }

    /**
     * How far price had already moved into the group, in the merged candle's own ranges.
     *
     * <p>Expressed as a multiple of the candle's range rather than a percentage on purpose: a 2%
     * move is large on a 15-minute candle and small on a daily, so a percentage threshold would
     * mean something different on every timeframe this feature offers.
     */
    private static double priorMoveInRanges(List<Bar> base, int start, Bar m, boolean bullish,
                                            WickReversalConfig cfg) {
        // Up to and including the group's first candle, which is where the move began.
        int from = Math.max(0, start - cfg.getContextBars());
        double range = CandlePatternDetector.range(m);
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

    /** How far the group's extreme sits inside the recent one, in ranges. Zero means it set it. */
    private static double distanceFromExtremeInRanges(List<Bar> base, int i, Bar m, boolean bullish,
                                                      WickReversalConfig cfg) {
        int from = Math.max(0, i - cfg.getSwingLookback());
        double range = CandlePatternDetector.range(m);
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

    /** How much of the move the last candle gave back, as a percentage of the move itself. */
    private static double recoveredPct(Bar first, Bar last, Bar m, boolean bullish) {
        if (bullish) {
            return first.open() > m.low()
                    ? (last.close() - m.low()) / (first.open() - m.low()) * 100 : Double.NaN;
        }
        return m.high() > first.open()
                ? (m.high() - last.close()) / (m.high() - first.open()) * 100 : Double.NaN;
    }

    /** The group's volume against the average volume of the groups before it. */
    private static double volumeRatio(List<Bar> merged, int i) {
        int from = Math.max(1, i - 20);
        if (from >= i) return 1;
        double sum = 0;
        for (int k = from; k < i; k++) sum += merged.get(k).volume();
        double avg = sum / (i - from);
        return avg > 0 ? merged.get(i).volume() / avg : 1;
    }

    /**
     * What has happened since the signal fired.
     *
     * <p>Judged on closes, not wicks. A bar that pokes through the level intraday and closes back
     * inside has not broken it — the same rule the breakout analyzer applies, and the reason this
     * scan does not report a spike as a confirmation.
     */
    static String[] statusOf(List<Bar> base, int i, Bar m, boolean bullish) {
        for (int k = i + 1; k < base.size(); k++) {
            double close = base.get(k).close();
            if (close > m.high()) {
                return bullish
                        ? new String[] {WickSignal.CONFIRMED,
                                "Price has since closed above the candle's high - the rejection was acted on."}
                        : new String[] {WickSignal.INVALIDATED,
                                "Price has since closed above the wick high - the rejection did not hold."};
            }
            if (close < m.low()) {
                return bullish
                        ? new String[] {WickSignal.INVALIDATED,
                                "Price has since closed below the wick low - the rejection did not hold."}
                        : new String[] {WickSignal.CONFIRMED,
                                "Price has since closed below the candle's low - the rejection was acted on."};
            }
        }
        return new String[] {WickSignal.PENDING,
                "Price is still inside the candle's range - the level is live."};
    }

    // ---------------------------------------------------------------- scoring
    // Five components, 100 points. Each one is a band rather than a curve so that a row's score can
    // be read off its own numbers without running the code. The bands are direction-neutral: they
    // read the rejection wick and the move rejected, whichever side of the candle those are on.

    /** 30 - how decisively the rejection wick dominates the body. */
    static double wickPoints(double wickToBody) {
        if (wickToBody >= 4) return 30;
        if (wickToBody >= 3) return 24;
        if (wickToBody >= 2.5) return 20;
        return 15;
    }

    /** 25 - how much of the move the last candle actually took back. */
    static double recoveryPoints(double closePosition) {
        if (closePosition >= 0.85) return 25;
        if (closePosition >= 0.7) return 19;
        if (closePosition >= 0.6) return 13;
        return 8;
    }

    /**
     * 20 - the move the group rejected.
     *
     * <p>A band that peaks and then falls away, not a ramp. A rejection after a long slide is the
     * setup; a rejection in the middle of a collapse is a falling knife, and a ramp would rank the
     * knife highest precisely because it had fallen furthest. The same holds upside down for a
     * bearish signal caught in the middle of a vertical rally.
     */
    static double priorMovePoints(double moveInRanges) {
        if (moveInRanges < 1.2) return 5;
        if (moveInRanges < 2) return 12;
        if (moveInRanges <= 4) return 20;
        if (moveInRanges <= 7) return 14;
        return 8;
    }

    /** 15 - whether the group marks the extreme rather than sitting inside one. */
    static double extremePoints(double distanceInRanges) {
        if (distanceInRanges <= 0.05) return 15;
        if (distanceInRanges <= 0.5) return 11;
        if (distanceInRanges <= 1.5) return 6;
        return 2;
    }

    /** 10 - conviction behind the group. */
    static double volumePoints(double volumeRatio) {
        if (volumeRatio >= 2) return 10;
        if (volumeRatio >= 1.5) return 8;
        if (volumeRatio >= 1) return 5;
        return 2;
    }

    private static double round(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? v : Math.round(v * 100) / 100.0;
    }
}
