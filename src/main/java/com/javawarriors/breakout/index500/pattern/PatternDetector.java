package com.javawarriors.breakout.index500.pattern;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;

/**
 * One pattern, one detector.
 *
 * <p>The seam that keeps patterns extensible: adding a new one means adding a class, not editing a
 * growing switch inside an analysis method. Every detector is a pure function of an
 * {@link IndicatorSnapshot} - the indicators are computed once per stock and handed to all of them,
 * so a detector never touches the network and never recomputes an EMA.
 *
 * <p>Several implementations are thin adapters over detectors that already existed for the Bullish
 * and Intraday tabs. That is deliberate: this feature needs the same Double Bottom that the bullish
 * ranking uses, and a second implementation of it would be two sources of truth for one question.
 */
public interface PatternDetector {

    /** Stable identifier used by the API filter, e.g. {@code DOUBLE_BOTTOM}. */
    String type();

    /** Human label for the dropdown, e.g. "Double Bottom". */
    String displayName();

    /** Never null - a detector that found nothing returns {@link PatternResult#absent}. */
    PatternResult detect(IndicatorSnapshot snapshot);
}
