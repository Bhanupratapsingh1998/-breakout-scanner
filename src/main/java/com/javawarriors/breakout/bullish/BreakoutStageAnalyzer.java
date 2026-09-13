package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Which level, if any, this stock has broken - and whether the break is real, worth 10 points.
 *
 * <p>Four level types are checked, strongest first: the 52-week high, the six-month high, the top
 * of a recent consolidation, and the detected pattern's own breakout level. The strongest cleared
 * level wins, because clearing a 52-week high is a different event from clearing a three-week
 * range even when both happen on the same bar.
 *
 * <p>Section 7's "avoid treating a weak intraday spike as a confirmed breakout" is enforced in
 * three places: the level must be cleared on a <em>close</em> (never a high), it must be cleared by
 * the confirmation buffer rather than by a tick, and the breakout bar's volume and closing position
 * within its own range are scored separately from the fact of the break. A break on half the usual
 * volume that closed on its low earns the level but almost none of the quality points.
 */
public final class BreakoutStageAnalyzer {

    public static final double MAX_POINTS = 10;

    /** Bars scanned back for the breakout bar; also the consolidation window. */
    private static final int BREAKOUT_SEARCH_BARS = 60;

    /** How far back a consolidation top is measured, excluding the current move. */
    private static final int CONSOLIDATION_LOOKBACK = 60;
    private static final int CONSOLIDATION_EXCLUDE = 5;

    /** How recently a breakout must have happened for its loss to still count as a failure. */
    private static final int FAILURE_LOOKBACK = 30;

    /**
     * @param type             NONE / CONSOLIDATION / PATTERN / SIX_MONTH / FIFTY_TWO_WEEK
     * @param level            the price that was (or must be) cleared
     * @param confirmedLevel   {@code level} plus the confirmation buffer
     * @param barsSinceBreakout 0 when the break is today, -1 when there has been no break
     * @param distancePct      current price relative to {@code level}; negative means still below
     */
    public record BreakoutStage(String type, String label, boolean confirmed, boolean nearBreakout,
                                boolean failed, double level, double confirmedLevel,
                                int breakoutBarIndex, int barsSinceBreakout, double distancePct,
                                double breakoutVolumeRatio, double breakoutBarClosePosition,
                                double points, String explanation) {

        public boolean hasLevel() {
            return !Double.isNaN(level) && level > 0;
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("label", label);
            row.put("confirmed", confirmed);
            row.put("nearBreakout", nearBreakout);
            row.put("failed", failed);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("explanation", explanation);
            if (hasLevel()) {
                row.put("level", level);
                row.put("confirmedLevel", confirmedLevel);
                row.put("distancePct", distancePct);
                row.put("barsSinceBreakout", barsSinceBreakout);
                if (breakoutBarIndex >= 0) {
                    row.put("breakoutVolumeRatio", breakoutVolumeRatio);
                    row.put("breakoutBarClosePosition", breakoutBarClosePosition);
                }
            }
            return row;
        }
    }

    private BreakoutStageAnalyzer() {
    }

    public static BreakoutStage analyze(IndicatorSnapshot s, ChartPattern pattern, BullishConfig cfg) {
        double buffer = 1 + cfg.getBreakoutConfirmBuffer();

        // Candidate levels, weakest first — the loop below keeps the strongest one that price has
        // actually cleared, and otherwise falls back to the nearest one still overhead.
        double consolidationTop = s.highestHighExcludingRecent(CONSOLIDATION_LOOKBACK, CONSOLIDATION_EXCLUDE);
        double sixMonthHigh = highExcludingRecent(s, IndicatorSnapshot.BARS_6M);
        double yearHigh = highExcludingRecent(s, IndicatorSnapshot.BARS_52W);
        double patternLevel = pattern.isPresent() ? pattern.resistance() : Double.NaN;

        String type = "NONE";
        double level = Double.NaN;
        if (valid(consolidationTop) && s.price > consolidationTop * buffer) {
            type = "CONSOLIDATION";
            level = consolidationTop;
        }
        if (valid(patternLevel) && s.price > patternLevel * buffer) {
            type = "PATTERN";
            level = patternLevel;
        }
        if (valid(sixMonthHigh) && s.price > sixMonthHigh * buffer) {
            type = "SIX_MONTH";
            level = sixMonthHigh;
        }
        if (valid(yearHigh) && s.price > yearHigh * buffer) {
            type = "FIFTY_TWO_WEEK";
            level = yearHigh;
        }

        boolean confirmed = !"NONE".equals(type);
        if (!confirmed) {
            // Before anything else: did this stock break out recently and then lose the level?
            // That has to be looked for explicitly, because resistance is recomputed from the
            // current window every time it is asked for — once price falls back, the failed
            // breakout's own high becomes the new resistance and the failure would otherwise read
            // as an ordinary stock sitting below a level it had never cleared.
            double failedLevel = failedBreakoutLevel(s, buffer);
            if (valid(failedLevel)) {
                return new BreakoutStage("FAILED", label("FAILED", false, false, true), false, false,
                        true, failedLevel, failedLevel * buffer, -1, -1,
                        (s.price - failedLevel) / failedLevel * 100, Double.NaN, Double.NaN, 0,
                        String.format("Cleared %.2f on a close within the last %d sessions and has"
                                + " since closed back below it — a failed breakout, not a retest.",
                                failedLevel, FAILURE_LOOKBACK));
            }

            // Nothing cleared. The level that matters is the nearest one still overhead — that is
            // what a "ready to break out" reading is measured against.
            double nearest = nearestOverhead(s, patternLevel, consolidationTop, sixMonthHigh, yearHigh);
            if (!valid(nearest)) {
                // Price is above every level but has not cleared any of them by the confirmation
                // buffer — it is pressed right against the strongest one. Without this fallback a
                // stock sitting exactly on its 52-week high would report no level at all, which is
                // the opposite of its situation.
                nearest = strongestLevel(patternLevel, consolidationTop, sixMonthHigh, yearHigh);
            }
            if (!valid(nearest)) {
                return new BreakoutStage("NONE", "No breakout level", false, false, false,
                        Double.NaN, Double.NaN, -1, -1, Double.NaN, Double.NaN, Double.NaN, 0,
                        "No resistance level close enough overhead to call a pending breakout.");
            }
            level = nearest;
            type = levelType(nearest, patternLevel, consolidationTop, sixMonthHigh, yearHigh);
        }

        double confirmedLevel = level * buffer;
        double distancePct = (s.price - level) / level * 100;
        boolean near = !confirmed && distancePct >= -cfg.getNearBreakoutBand() * 100;

        int breakoutBarIndex = confirmed ? findBreakoutBar(s, confirmedLevel) : -1;
        int barsSince = breakoutBarIndex < 0 ? -1 : s.n - 1 - breakoutBarIndex;

        double breakoutVolRatio = Double.NaN;
        double closePosition = Double.NaN;
        if (breakoutBarIndex >= 0) {
            double volMa = averageVolumeBefore(s, breakoutBarIndex, 20);
            breakoutVolRatio = volMa <= 0 ? Double.NaN : s.volume[breakoutBarIndex] / volMa;
            double range = s.high[breakoutBarIndex] - s.low[breakoutBarIndex];
            closePosition = range <= 0 ? 1
                    : (s.close[breakoutBarIndex] - s.low[breakoutBarIndex]) / range;
        }

        // Reaching here means price closed above the confirm line, so the break is live by
        // definition — failure is only ever detected in the not-confirmed branch above.
        double points = score(type, confirmed, near, false, breakoutVolRatio, closePosition,
                barsSince, cfg);

        return new BreakoutStage(type, label(type, confirmed, near, false), confirmed, near, false,
                level, confirmedLevel, breakoutBarIndex, barsSince, distancePct,
                breakoutVolRatio, closePosition, points,
                explain(type, confirmed, near, false, distancePct, breakoutVolRatio, closePosition));
    }

    private static double score(String type, boolean confirmed, boolean near, boolean failed,
                                double volRatio, double closePosition, int barsSince,
                                BullishConfig cfg) {
        if (failed) return 0;

        // Which level was cleared (0-4).
        double levelPoints;
        if (!confirmed) levelPoints = near ? 2 : 0;
        else levelPoints = switch (type) {
            case "FIFTY_TWO_WEEK" -> 4;
            case "SIX_MONTH" -> 3.5;
            case "PATTERN" -> 3;
            case "CONSOLIDATION" -> 2.5;
            default -> 0;
        };

        // Volume behind the break (0-3). Unknown scores as a weak-but-not-zero 1.
        double volumePoints;
        if (Double.isNaN(volRatio)) volumePoints = confirmed ? 1 : 0;
        else if (volRatio >= cfg.getBreakoutVolumeRatio() * 1.5) volumePoints = 3;
        else if (volRatio >= cfg.getBreakoutVolumeRatio()) volumePoints = 2.5;
        else if (volRatio >= 1.0) volumePoints = 1;
        else volumePoints = 0;

        // Who won the breakout day (0-2): a close in the top third of the bar's own range.
        double closePoints;
        if (Double.isNaN(closePosition)) closePoints = 0;
        else if (closePosition >= 0.7) closePoints = 2;
        else if (closePosition >= 0.5) closePoints = 1.2;
        else closePoints = 0;

        // Follow-through (0-1): the break has survived at least a few sessions.
        double followThrough = barsSince >= 3 ? 1 : barsSince >= 1 ? 0.5 : 0;

        return levelPoints + volumePoints + closePoints + followThrough;
    }

    private static String label(String type, boolean confirmed, boolean near, boolean failed) {
        if (failed) return "FAILED BREAKOUT";
        if (confirmed) {
            return switch (type) {
                case "FIFTY_TWO_WEEK" -> "52-WEEK BREAKOUT";
                case "SIX_MONTH" -> "6-MONTH BREAKOUT";
                case "PATTERN" -> "PATTERN BREAKOUT";
                case "CONSOLIDATION" -> "CONSOLIDATION BREAKOUT";
                default -> "BREAKOUT";
            };
        }
        return near ? "APPROACHING BREAKOUT" : "BELOW RESISTANCE";
    }

    private static String explain(String type, boolean confirmed, boolean near, boolean failed,
                                  double distancePct, double volRatio, double closePosition) {
        if (failed) {
            return String.format("Broke out but has closed back below the level (%.1f%% away) —"
                    + " treated as a failed breakout, not a retest.", distancePct);
        }
        if (!confirmed) {
            return near
                    ? String.format("Sitting %.1f%% below its %s level and coiling under it.",
                            -distancePct, humanType(type))
                    : String.format("Still %.1f%% below its %s level.", -distancePct, humanType(type));
        }
        StringBuilder sb = new StringBuilder(String.format("Closed %.1f%% clear of its %s level",
                distancePct, humanType(type)));
        if (!Double.isNaN(volRatio)) sb.append(String.format(" on %.1fx average volume", volRatio));
        if (!Double.isNaN(closePosition)) {
            sb.append(closePosition >= 0.7 ? ", closing near the day's high"
                    : closePosition >= 0.5 ? ", closing mid-range"
                    : ", but closing in the lower half of the day's range");
        }
        return sb.append('.').toString();
    }

    private static String humanType(String type) {
        return switch (type) {
            case "FIFTY_TWO_WEEK" -> "52-week high";
            case "SIX_MONTH" -> "six-month high";
            case "PATTERN" -> "pattern";
            case "CONSOLIDATION" -> "consolidation";
            default -> "resistance";
        };
    }

    /** Earliest bar in the recent window whose close cleared the confirm line and stayed the trigger. */
    private static int findBreakoutBar(IndicatorSnapshot s, double confirmedLevel) {
        int from = Math.max(1, s.n - BREAKOUT_SEARCH_BARS);
        for (int i = from; i < s.n; i++) {
            if (s.close[i] > confirmedLevel && s.close[i - 1] <= confirmedLevel) return i;
        }
        // Already above the level for the whole window — the break predates it.
        return s.close[from] > confirmedLevel ? from : -1;
    }

    /**
     * The level of the most recent breakout that has since been lost, or NaN if there isn't one.
     *
     * <p>Each candidate bar is judged against the resistance that existed <em>at the time</em> -
     * the highest high of the sixty sessions before it - rather than against today's resistance,
     * which the failed breakout itself now defines. Scanning backwards returns the most recent
     * failure, which is the one that describes where the stock is now.
     */
    private static double failedBreakoutLevel(IndicatorSnapshot s, double buffer) {
        for (int i = s.n - 1; i >= Math.max(1, s.n - FAILURE_LOOKBACK); i--) {
            double levelThen = maxHighBetween(s, i - CONSOLIDATION_EXCLUDE - CONSOLIDATION_LOOKBACK,
                    i - CONSOLIDATION_EXCLUDE);
            if (!valid(levelThen)) continue;
            if (s.close[i] > levelThen * buffer && s.price < levelThen) return levelThen;
        }
        return Double.NaN;
    }

    private static double maxHighBetween(IndicatorSnapshot s, int from, int toExclusive) {
        int a = Math.max(0, from), b = Math.min(toExclusive, s.n);
        if (b <= a) return Double.NaN;
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = a; i < b; i++) hi = Math.max(hi, s.high[i]);
        return hi;
    }

    private static double averageVolumeBefore(IndicatorSnapshot s, int index, int period) {
        int from = Math.max(0, index - period);
        if (index <= from) return 0;
        double sum = 0;
        for (int i = from; i < index; i++) sum += s.volume[i];
        return sum / (index - from);
    }

    private static double highExcludingRecent(IndicatorSnapshot s, int lookback) {
        return s.highestHighExcludingRecent(lookback, CONSOLIDATION_EXCLUDE);
    }

    private static double nearestOverhead(IndicatorSnapshot s, double... levels) {
        double nearest = Double.NaN;
        for (double l : levels) {
            if (!valid(l) || l <= s.price) continue;
            if (Double.isNaN(nearest) || l < nearest) nearest = l;
        }
        return nearest;
    }

    /** The highest of the candidate levels — the one price is pressing against. */
    private static double strongestLevel(double... levels) {
        double best = Double.NaN;
        for (double l : levels) {
            if (!valid(l)) continue;
            if (Double.isNaN(best) || l > best) best = l;
        }
        return best;
    }

    private static String levelType(double level, double pattern, double consolidation,
                                    double sixMonth, double year) {
        if (valid(pattern) && level == pattern) return "PATTERN";
        if (valid(year) && level == year) return "FIFTY_TWO_WEEK";
        if (valid(sixMonth) && level == sixMonth) return "SIX_MONTH";
        if (valid(consolidation) && level == consolidation) return "CONSOLIDATION";
        return "NONE";
    }

    private static boolean valid(double level) {
        return !Double.isNaN(level) && !Double.isInfinite(level) && level > 0;
    }
}
