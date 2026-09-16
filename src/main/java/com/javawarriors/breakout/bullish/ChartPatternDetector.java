package com.javawarriors.breakout.bullish;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Objective detection of the six bullish chart patterns the spec asks for, worth 15 points.
 *
 * <p>The rule this class is built around is section 6's: <em>do not classify a pattern merely
 * because the chart resembles one.</em> Every detector here is a set of numeric tests over swing
 * pivots, ranges and volume - no shape matching, no "looks like". A candidate that fails any
 * structural test is not reported at a lower confidence, it is not reported at all, and a stock
 * with nothing valid returns {@link ChartPattern#none()} rather than being fitted to the nearest
 * pattern.
 *
 * <p>Confidence is a sum of named evidence on top of a low base for bare structural validity, and
 * is clamped at 95. It never reaches 100 deliberately: a pattern is an observation about past
 * price, not a fact about the future, and a 100 on the dashboard would read as a promise.
 *
 * <p>When several patterns validate - a flat base inside a cup's handle, say - the highest
 * confidence wins, with the spec's priority order breaking ties.
 */
public final class ChartPatternDetector {

    public static final double MAX_POINTS = 15;

    /**
     * What bare structural validity is worth on its own - deliberately below any sensible floor.
     *
     * <p>Each detector searches many window combinations (the flag alone tries 16 lengths against
     * 11 pole lengths) and keeps whichever scores highest. Across ~100 candidates per stock, a
     * shape that merely passes the structural tests turns up almost every time, so structure alone
     * cannot be evidence of anything: a live Nifty 500 run with the base at 55 matched a pattern
     * for 96% of stocks and the confidences saturated at a median of 86 out of 95, which is a scale
     * that has stopped measuring. Starting at 40 means the corroborating evidence - volume dry-up,
     * symmetry, a well-centred low, a decisive pole - is what carries a candidate over the
     * configured floor, which is what confidence is supposed to mean.
     */
    private static final double BASE_CONFIDENCE = 40;

    /** No pattern is ever certain, so confidence never reads 100. */
    private static final double MAX_CONFIDENCE = 95;

    /** Spec priority (section 6), used only to break confidence ties. */
    private static final List<String> PRIORITY = List.of(
            "BULL_FLAG", "CUP_AND_HANDLE", "DOUBLE_BOTTOM",
            "ASCENDING_TRIANGLE", "FLAT_BASE", "INVERSE_HEAD_AND_SHOULDERS");

    /**
     * @param resistance        the level the pattern is pressing against (neckline / rim / flat top)
     * @param breakoutLevel     resistance plus the confirmation buffer - what a close must clear
     * @param invalidationLevel below this the pattern is broken, not merely pulling back
     * @param target            measured move projected from the pattern's own height
     */
    public record ChartPattern(String type, String name, double confidence, double points,
                               double resistance, double breakoutLevel, double invalidationLevel,
                               double target, int startIndex, int endIndex, String explanation) {

        public static ChartPattern none() {
            return new ChartPattern("NO_CLEAR_PATTERN", "No clear pattern", 0, 0,
                    Double.NaN, Double.NaN, Double.NaN, Double.NaN, -1, -1,
                    "No pattern met its structural tests - none has been forced onto this chart.");
        }

        public boolean isPresent() {
            return !"NO_CLEAR_PATTERN".equals(type);
        }

        public Map<String, Object> toRow() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("name", name);
            row.put("confidence", confidence);
            row.put("points", points);
            row.put("maxPoints", MAX_POINTS);
            row.put("explanation", explanation);
            if (isPresent()) {
                row.put("resistance", resistance);
                row.put("breakoutLevel", breakoutLevel);
                row.put("invalidationLevel", invalidationLevel);
                row.put("target", target);
                row.put("startIndex", startIndex);
                row.put("endIndex", endIndex);
            }
            return row;
        }
    }

    private ChartPatternDetector() {
    }

    /** Best validated pattern, or {@link ChartPattern#none()} when nothing clears the bar. */
    public static ChartPattern detect(IndicatorSnapshot s, BullishConfig cfg) {
        List<ChartPattern> found = new ArrayList<>();
        addIfPresent(found, bullFlag(s, cfg));
        addIfPresent(found, cupAndHandle(s, cfg));
        addIfPresent(found, doubleBottom(s, cfg));
        addIfPresent(found, ascendingTriangle(s, cfg));
        addIfPresent(found, flatBase(s, cfg));
        addIfPresent(found, inverseHeadAndShoulders(s, cfg));

        return found.stream()
                .filter(p -> p.confidence() >= cfg.getMinPatternConfidence())
                .max((a, b) -> {
                    int byConfidence = Double.compare(a.confidence(), b.confidence());
                    if (byConfidence != 0) return byConfidence;
                    return Integer.compare(PRIORITY.indexOf(b.type()), PRIORITY.indexOf(a.type()));
                })
                .orElse(ChartPattern.none());
    }

    private static void addIfPresent(List<ChartPattern> out, ChartPattern p) {
        if (p != null && p.isPresent()) out.add(p);
    }

    // ------------------------------------------------------------------ bull flag

    /**
     * A sharp advance (the pole) followed by a shallow, quiet drift (the flag).
     *
     * <p>The tests that make it a flag rather than "a stock that went up and then went sideways":
     * the drift must retrace no more than half the pole, its own range must be a fraction of the
     * pole's, and volume must be lighter in the flag than in the pole. A deep or noisy pullback on
     * heavy volume is a failed advance, and is rejected here.
     */
    public static ChartPattern bullFlag(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        ChartPattern best = null;

        for (int flagLen = 5; flagLen <= 20; flagLen++) {
            int flagStart = n - flagLen;
            for (int poleLen = 10; poleLen <= 30; poleLen += 2) {
                int poleStart = flagStart - poleLen;
                if (poleStart < 1) continue;

                double poleBase = s.close[poleStart];
                if (poleBase <= 0) continue;
                double poleGain = (s.close[flagStart - 1] - poleBase) / poleBase;
                if (poleGain < 0.15) continue;

                double poleHigh = maxHigh(s, poleStart, flagStart);
                double poleLow = minLow(s, poleStart, flagStart);
                double poleHeight = poleHigh - poleLow;
                if (poleHeight <= 0) continue;

                double flagHigh = maxHigh(s, flagStart, n);
                double flagLow = minLow(s, flagStart, n);

                // The flag must sit on top of the pole, not unwind it.
                double retrace = (poleHigh - flagLow) / poleHeight;
                if (retrace > 0.5) continue;

                double flagRangePct = flagLow <= 0 ? Double.MAX_VALUE : (flagHigh - flagLow) / flagLow;
                if (flagRangePct > Math.max(0.06, poleGain * 0.4)) continue;

                double poleVol = avgVolume(s, poleStart, flagStart);
                double flagVol = avgVolume(s, flagStart, n);
                if (poleVol <= 0 || flagVol > poleVol) continue;   // a quiet flag is the whole point

                double confidence = BASE_CONFIDENCE;
                if (flagVol < poleVol * 0.7) confidence += 10;     // genuine dry-up, not a rounding
                if (flagRangePct <= 0.04) confidence += 10;        // tight coil
                if (retrace <= 0.33) confidence += 8;              // shallow retrace
                if (poleGain >= 0.25) confidence += 7;             // decisive pole
                if (s.price >= flagLow + 0.6 * (flagHigh - flagLow)) confidence += 5;
                confidence = Math.min(confidence, MAX_CONFIDENCE);

                ChartPattern candidate = build("BULL_FLAG", "Bull Flag", confidence, flagHigh,
                        poleStart, n - 1, flagLow, flagHigh + poleHeight, cfg,
                        String.format("A %.0f%% advance over %d sessions, then a %d-session drift that"
                                        + " gave back only %.0f%% of it on lighter volume.",
                                poleGain * 100, poleLen, flagLen, retrace * 100));
                if (best == null || candidate.confidence() > best.confidence()) best = candidate;
            }
        }
        return best == null ? ChartPattern.none() : best;
    }

    // -------------------------------------------------------------- cup and handle

    /**
     * A rounded correction back to roughly the prior high, then a shallow handle.
     *
     * <p>"Rounded" is tested, not eyeballed: the cup's low must fall in the middle third of its
     * span. A low at the very start or end is a V-shaped drop or a straight recovery, both of
     * which behave differently from a cup and neither of which is accepted here.
     */
    public static ChartPattern cupAndHandle(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        List<Integer> highs = s.swingHighs;
        if (highs.size() < 2) return ChartPattern.none();

        ChartPattern best = null;
        for (int a = 0; a < highs.size() - 1; a++) {
            int leftRimIdx = highs.get(a);
            if (n - leftRimIdx > 300 || n - leftRimIdx < 40) continue;
            double leftRim = s.high[leftRimIdx];
            if (leftRim <= 0) continue;

            for (int b = a + 1; b < highs.size(); b++) {
                int rightRimIdx = highs.get(b);
                int cupLen = rightRimIdx - leftRimIdx;
                if (cupLen < 30 || cupLen > 250) continue;

                double rightRim = s.high[rightRimIdx];
                // The right rim must return to the left rim's neighbourhood; a much lower right
                // rim is a failed recovery, a much higher one is simply a new advance.
                double rimMismatch = Math.abs(rightRim - leftRim) / leftRim;
                if (rimMismatch > 0.06) continue;

                int cupLowIdx = minLowIndex(s, leftRimIdx, rightRimIdx + 1);
                double cupLow = s.low[cupLowIdx];
                double depth = (leftRim - cupLow) / leftRim;
                if (depth < 0.12 || depth > 0.45) continue;        // too shallow / a crash, not a cup

                double position = (double) (cupLowIdx - leftRimIdx) / cupLen;
                if (position < 0.30 || position > 0.70) continue;  // the roundness test

                // Handle: a shallow pullback after the right rim, staying in the cup's upper half.
                int handleLen = n - rightRimIdx;
                if (handleLen < 3 || handleLen > 35) continue;
                double handleLow = minLow(s, rightRimIdx, n);
                if (handleLow < cupLow + 0.5 * (rightRim - cupLow)) continue;
                double handleDepth = (rightRim - handleLow) / rightRim;
                if (handleDepth > 0.15) continue;
                if (s.price < handleLow) continue;

                double cupVol = avgVolume(s, leftRimIdx, rightRimIdx);
                double handleVol = avgVolume(s, rightRimIdx, n);

                double confidence = BASE_CONFIDENCE;
                if (depth >= 0.15 && depth <= 0.33) confidence += 10;      // the classic depth band
                if (position >= 0.40 && position <= 0.60) confidence += 8; // well-centred low
                if (handleDepth <= 0.08) confidence += 8;                  // shallow handle
                if (cupVol > 0 && handleVol < cupVol * 0.8) confidence += 8;
                if (rimMismatch <= 0.02) confidence += 6;
                confidence = Math.min(confidence, MAX_CONFIDENCE);

                double rim = Math.max(leftRim, rightRim);
                ChartPattern candidate = build("CUP_AND_HANDLE", "Cup & Handle", confidence, rim,
                        leftRimIdx, n - 1, handleLow, rim + (rim - cupLow), cfg,
                        String.format("A %.0f%%-deep rounded base over %d sessions with its low in the"
                                        + " middle of the span, recovered to within %.1f%% of the old"
                                        + " high, now in a %.0f%% handle.",
                                depth * 100, cupLen, rimMismatch * 100, handleDepth * 100));
                if (best == null || candidate.confidence() > best.confidence()) best = candidate;
            }
        }
        return best == null ? ChartPattern.none() : best;
    }

    // --------------------------------------------------------------- double bottom

    /**
     * Two lows at roughly the same level with a meaningful rally between them.
     *
     * <p>The intervening peak has to be at least 8% above the lows. Without that test any two
     * nearby lows in a sideways drift qualify, and a double bottom whose neckline is 2% overhead
     * has no measured move worth trading.
     */
    public static ChartPattern doubleBottom(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        List<Integer> lows = s.swingLows;
        if (lows.size() < 2) return ChartPattern.none();

        ChartPattern best = null;
        for (int a = 0; a < lows.size() - 1; a++) {
            for (int b = a + 1; b < lows.size(); b++) {
                int i1 = lows.get(a);
                int i2 = lows.get(b);
                int gap = i2 - i1;
                if (gap < 15 || gap > 120) continue;
                if (n - i2 > 90) continue;                 // the second low must still be recent

                double l1 = s.low[i1];
                double l2 = s.low[i2];
                if (l1 <= 0) continue;
                double mismatch = Math.abs(l2 - l1) / l1;
                if (mismatch > 0.04) continue;             // "double", not "lower low"

                double peak = maxHigh(s, i1, i2 + 1);
                double avgLow = (l1 + l2) / 2;
                double height = (peak - avgLow) / avgLow;
                if (height < 0.08) continue;

                // The lows must be the floor of the region, not two dips inside a continuing decline.
                if (minLow(s, Math.max(0, i1 - 20), i1) < l1 * 0.98) continue;
                if (s.price < avgLow) continue;

                double confidence = BASE_CONFIDENCE;
                if (mismatch <= 0.015) confidence += 10;                       // near-identical lows
                if (height >= 0.15) confidence += 8;                           // room to the neckline
                if (avgVolume(s, i2, Math.min(n, i2 + 5)) < avgVolume(s, i1, Math.min(n, i1 + 5))) {
                    confidence += 7;   // lighter selling on the retest of the low
                }
                if (s.price > avgLow + 0.5 * (peak - avgLow)) confidence += 6; // already recovering
                if (s.lastEma50 > s.lastEma200) confidence += 4;
                confidence = Math.min(confidence, MAX_CONFIDENCE);

                ChartPattern candidate = build("DOUBLE_BOTTOM", "Double Bottom", confidence, peak,
                        i1, n - 1, Math.min(l1, l2), peak + (peak - avgLow), cfg,
                        String.format("Two lows %d sessions apart within %.1f%% of each other,"
                                        + " separated by a %.0f%% rally to the neckline.",
                                gap, mismatch * 100, height * 100));
                if (best == null || candidate.confidence() > best.confidence()) best = candidate;
            }
        }
        return best == null ? ChartPattern.none() : best;
    }

    // ---------------------------------------------------------- ascending triangle

    /**
     * A flat ceiling with a rising floor - supply at one price being absorbed by buyers who keep
     * paying more.
     *
     * <p>Needs at least two highs within 3% of each other and at least two rising lows, with the
     * last low materially above the first. Rising lows alone are just an uptrend; the flat ceiling
     * is what makes it a triangle.
     */
    public static ChartPattern ascendingTriangle(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        ChartPattern best = null;

        for (int window = 30; window <= 120; window += 10) {
            final int from = n - window;
            if (from < 5) continue;

            List<Integer> highs = s.swingHighs.stream().filter(i -> i >= from).toList();
            List<Integer> lows = s.swingLows.stream().filter(i -> i >= from).toList();
            if (highs.size() < 2 || lows.size() < 2) continue;

            double hiMax = Double.NEGATIVE_INFINITY, hiMin = Double.POSITIVE_INFINITY, hiSum = 0;
            for (int idx : highs) {
                hiMax = Math.max(hiMax, s.high[idx]);
                hiMin = Math.min(hiMin, s.high[idx]);
                hiSum += s.high[idx];
            }
            if (hiMin <= 0) continue;
            double ceilingSpread = (hiMax - hiMin) / hiMin;
            if (ceilingSpread > 0.03) continue;                          // the ceiling must be flat
            double resistance = hiSum / highs.size();

            double firstLow = s.low[lows.get(0)];
            double lastLow = s.low[lows.get(lows.size() - 1)];
            if (firstLow <= 0) continue;
            double floorRise = (lastLow - firstLow) / firstLow;
            if (floorRise < 0.03) continue;

            boolean monotonic = true;
            for (int i = 1; i < lows.size(); i++) {
                if (s.low[lows.get(i)] <= s.low[lows.get(i - 1)]) { monotonic = false; break; }
            }
            if (!monotonic) continue;
            if (s.price > resistance * 1.05) continue;   // already well through it; not a triangle now

            double confidence = BASE_CONFIDENCE;
            if (highs.size() >= 3) confidence += 10;              // more touches, more visible supply
            if (lows.size() >= 3) confidence += 6;
            if (ceilingSpread <= 0.015) confidence += 8;          // very flat ceiling
            if (floorRise >= 0.08) confidence += 6;               // steeply rising floor
            if (s.price >= resistance * 0.97) confidence += 5;    // coiled right under it
            confidence = Math.min(confidence, MAX_CONFIDENCE);

            ChartPattern candidate = build("ASCENDING_TRIANGLE", "Ascending Triangle", confidence,
                    resistance, from, n - 1, lastLow, resistance + (resistance - firstLow), cfg,
                    String.format("%d highs within %.1f%% of %.2f while the lows rose %.0f%% over %d"
                                    + " sessions - supply at one level being absorbed.",
                            highs.size(), ceilingSpread * 100, resistance, floorRise * 100, window));
            if (best == null || candidate.confidence() > best.confidence()) best = candidate;
        }
        return best == null ? ChartPattern.none() : best;
    }

    // ------------------------------------------------------------------- flat base

    /**
     * A tight sideways range near the highs, after a prior advance.
     *
     * <p>The prior-advance requirement is what stops this matching every quiet stock in the
     * universe: a flat base is a pause in a move, and without a move before it the same tight
     * range is just a stock nobody is trading.
     */
    public static ChartPattern flatBase(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        ChartPattern best = null;
        double sixMonthHigh = s.highestHigh(IndicatorSnapshot.BARS_6M);

        for (int baseLen = 25; baseLen <= 70; baseLen += 5) {
            int baseStart = n - baseLen;
            int priorStart = baseStart - 60;
            if (priorStart < 1) continue;

            double baseHigh = maxHigh(s, baseStart, n);
            double baseLow = minLow(s, baseStart, n);
            if (baseLow <= 0) continue;
            double rangePct = (baseHigh - baseLow) / baseLow;
            if (rangePct > 0.12) continue;

            double priorBase = s.close[priorStart];
            if (priorBase <= 0) continue;
            double priorGain = (s.close[baseStart] - priorBase) / priorBase;
            if (priorGain < 0.20) continue;

            if (sixMonthHigh > 0 && baseHigh < sixMonthHigh * 0.92) continue;  // must be near the highs

            double priorVol = avgVolume(s, priorStart, baseStart);
            double baseVol = avgVolume(s, baseStart, n);

            double confidence = BASE_CONFIDENCE;
            if (rangePct <= 0.07) confidence += 10;                          // genuinely tight
            if (priorGain >= 0.35) confidence += 8;
            if (priorVol > 0 && baseVol < priorVol * 0.8) confidence += 8;   // volume dried up
            if (baseHigh >= sixMonthHigh * 0.98) confidence += 6;            // pressing the high
            if (s.price >= baseLow + 0.6 * (baseHigh - baseLow)) confidence += 4;
            confidence = Math.min(confidence, MAX_CONFIDENCE);

            ChartPattern candidate = build("FLAT_BASE", "Flat Base", confidence, baseHigh,
                    baseStart, n - 1, baseLow, baseHigh + (baseHigh - baseLow), cfg,
                    String.format("A %.0f%% advance, then %d sessions in a %.1f%%-wide range just"
                                    + " under the six-month high.",
                            priorGain * 100, baseLen, rangePct * 100));
            if (best == null || candidate.confidence() > best.confidence()) best = candidate;
        }
        return best == null ? ChartPattern.none() : best;
    }

    // ------------------------------------------------ inverse head and shoulders

    /**
     * Three lows with the middle one deepest, under a neckline.
     *
     * <p>The neckline is taken as the higher of the two intervening peaks rather than a fitted
     * sloping line. That is a deliberate simplification: it is the more conservative of the two -
     * a breakout must clear the higher peak, never a line sloping down beneath it - and it keeps
     * the level a real price that appears on the chart.
     */
    public static ChartPattern inverseHeadAndShoulders(IndicatorSnapshot s, BullishConfig cfg) {
        int n = s.n;
        final int from = n - 180;
        List<Integer> lows = s.swingLows.stream().filter(i -> i >= from).toList();
        if (lows.size() < 3) return ChartPattern.none();

        ChartPattern best = null;
        for (int a = 0; a < lows.size() - 2; a++) {
            for (int h = a + 1; h < lows.size() - 1; h++) {
                for (int b = h + 1; b < lows.size(); b++) {
                    int lsIdx = lows.get(a), headIdx = lows.get(h), rsIdx = lows.get(b);
                    double ls = s.low[lsIdx], head = s.low[headIdx], rs = s.low[rsIdx];
                    if (ls <= 0 || rs <= 0) continue;
                    if (head >= ls * 0.97 || head >= rs * 0.97) continue;   // the head must be deepest

                    double shoulderMismatch = Math.abs(ls - rs) / Math.min(ls, rs);
                    if (shoulderMismatch > 0.10) continue;                  // shoulders roughly level

                    if (rsIdx - lsIdx < 30 || rsIdx - lsIdx > 180) continue;
                    if (n - rsIdx > 40) continue;                           // right shoulder is stale

                    double peak1 = maxHigh(s, lsIdx, headIdx + 1);
                    double peak2 = maxHigh(s, headIdx, rsIdx + 1);
                    double neckline = Math.max(peak1, peak2);
                    if (neckline <= Math.max(ls, rs)) continue;
                    if (s.price < head) continue;

                    double headDepth = (neckline - head) / neckline;
                    if (headDepth < 0.08) continue;

                    double necklineTilt = Math.abs(peak1 - peak2) / Math.min(peak1, peak2);

                    double confidence = BASE_CONFIDENCE;
                    if (shoulderMismatch <= 0.04) confidence += 10;   // symmetric shoulders
                    if (necklineTilt <= 0.03) confidence += 8;        // level neckline
                    if (headDepth >= 0.15) confidence += 7;
                    if (s.price >= neckline * 0.95) confidence += 6;  // pressing the neckline
                    if (s.lastEma20 > s.lastEma50) confidence += 4;
                    confidence = Math.min(confidence, MAX_CONFIDENCE);

                    ChartPattern candidate = build("INVERSE_HEAD_AND_SHOULDERS",
                            "Inverse Head & Shoulders", confidence, neckline, lsIdx, n - 1, rs,
                            neckline + (neckline - head), cfg,
                            String.format("Shoulders within %.1f%% of each other around a head %.0f%%"
                                            + " below a %.1f%%-level neckline at %.2f.",
                                    shoulderMismatch * 100, headDepth * 100, necklineTilt * 100,
                                    neckline));
                    if (best == null || candidate.confidence() > best.confidence()) best = candidate;
                }
            }
        }
        return best == null ? ChartPattern.none() : best;
    }

    // ------------------------------------------------------------------- helpers

    private static ChartPattern build(String type, String name, double confidence, double resistance,
                                      int startIndex, int endIndex, double invalidation, double target,
                                      BullishConfig cfg, String explanation) {
        double points = Math.min(MAX_POINTS, confidence / 100.0 * MAX_POINTS);
        return new ChartPattern(type, name, confidence, points, resistance,
                resistance * (1 + cfg.getBreakoutConfirmBuffer()), invalidation, target,
                startIndex, endIndex, explanation);
    }

    static double maxHigh(IndicatorSnapshot s, int from, int toExclusive) {
        double hi = Double.NEGATIVE_INFINITY;
        for (int i = Math.max(0, from); i < Math.min(toExclusive, s.n); i++) hi = Math.max(hi, s.high[i]);
        return hi;
    }

    static double minLow(IndicatorSnapshot s, int from, int toExclusive) {
        double lo = Double.POSITIVE_INFINITY;
        for (int i = Math.max(0, from); i < Math.min(toExclusive, s.n); i++) lo = Math.min(lo, s.low[i]);
        return lo;
    }

    private static int minLowIndex(IndicatorSnapshot s, int from, int toExclusive) {
        int best = Math.max(0, from);
        for (int i = Math.max(0, from); i < Math.min(toExclusive, s.n); i++) {
            if (s.low[i] < s.low[best]) best = i;
        }
        return best;
    }

    private static double avgVolume(IndicatorSnapshot s, int from, int toExclusive) {
        int a = Math.max(0, from), b = Math.min(toExclusive, s.n);
        if (b <= a) return 0;
        double sum = 0;
        for (int i = a; i < b; i++) sum += s.volume[i];
        return sum / (b - a);
    }
}
