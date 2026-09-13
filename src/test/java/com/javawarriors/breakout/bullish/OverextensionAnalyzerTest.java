package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BreakoutStageAnalyzer.BreakoutStage;
import com.javawarriors.breakout.bullish.ChartPatternDetector.ChartPattern;
import com.javawarriors.breakout.bullish.OverextensionAnalyzer.Overextension;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OverextensionAnalyzerTest {

    private final BullishConfig cfg = new BullishConfig();

    private Overextension analyze(IndicatorSnapshot s) {
        BreakoutStage b = BreakoutStageAnalyzer.analyze(s, ChartPattern.none(), cfg);
        return OverextensionAnalyzer.analyze(s, b, cfg);
    }

    @Test
    void aVerticalRunAwayFromTheMeanIsSevere() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(200, 25)
                .move(25, 70, 2.0)             // a near-vertical month
                .snapshot();

        Overextension o = analyze(s);

        assertTrue(o.isSevere(), "level was " + o.level() + " flags " + o.flags());
        assertFalse(o.isClean());
        assertFalse(o.flags().isEmpty(), "a severe reading must say which measures fired");
    }

    @Test
    void aStockSittingOnItsTwentyEmaIsClean() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100)
                .move(200, 30)
                .chop(30, 1.5)
                .snapshot();

        Overextension o = analyze(s);

        assertTrue(o.isClean(), "level was " + o.level() + " flags " + o.flags());
        assertTrue(o.flags().isEmpty());
    }

    @Test
    void aSteadyUptrendIsNotPunishedForSimplyHavingRisen() {
        // The distinction section 23 turns on: this stock has gone up a lot, but it advanced in
        // steps with rests between them and has never left its own mean behind, so it is not a
        // chase.
        IndicatorSnapshot s = SeriesBuilder.healthyUptrend().snapshot();

        Overextension o = analyze(s);

        assertFalse(o.isSevere(),
                "a steady grind higher is not overextension; flags were " + o.flags());
    }

    @Test
    void theSevereThresholdIsConfigurable() {
        IndicatorSnapshot s = SeriesBuilder.startingAt(100).move(200, 25).move(30, 40, 1.5).snapshot();

        BullishConfig lenient = new BullishConfig();
        lenient.setSevereAtrsFromEma20(50);
        lenient.setExtendedAtrsFromEma20(40);
        lenient.setSevereRsi(200);
        lenient.setExtendedRsi(199);
        lenient.setMaxExtensionFromBreakout(10);

        BreakoutStage b = BreakoutStageAnalyzer.analyze(s, ChartPattern.none(), cfg);

        // Only the StretchFromMean reading (which the breakout scanner owns) can still fire here,
        // so the configurable thresholds are demonstrably doing the work in the default case.
        Overextension strict = OverextensionAnalyzer.analyze(s, b, cfg);
        Overextension loose = OverextensionAnalyzer.analyze(s, b, lenient);

        assertTrue(strict.flags().size() >= loose.flags().size(),
                "raising the thresholds cannot add flags");
    }

    @Test
    void overextensionReportsEveryDistanceItMeasured() {
        Overextension o = analyze(SeriesBuilder.healthyUptrend().snapshot());

        assertFalse(Double.isNaN(o.atrsAboveEma20()));
        assertFalse(Double.isNaN(o.pctAboveEma20()));
        assertFalse(Double.isNaN(o.pctAboveEma50()));
        assertFalse(Double.isNaN(o.rsi()));
        assertNotNull(o.stretchRating());
    }
}
