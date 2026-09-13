package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;
import com.javawarriors.breakout.model.Bar;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MarketRegimeAnalyzerTest {

    private final BullishConfig cfg = new BullishConfig();

    private static List<Bar> rising() {
        return SeriesBuilder.startingAt(20000).move(300, 28).build();
    }

    private static List<Bar> falling() {
        return SeriesBuilder.startingAt(20000).move(300, -30).build();
    }

    @Test
    void twoRisingIndicesAreABullishRegime() {
        Regime r = MarketRegimeAnalyzer.analyze(rising(), rising(), cfg);

        assertEquals("BULLISH", r.regime());
        assertTrue(r.isBullish());
        assertTrue(r.allowsBuyNow());
        assertEquals(1.0, r.scoreMultiplier());
        assertTrue(r.summary().contains("Nifty 500"), r.summary());
    }

    @Test
    void twoFallingIndicesAreABearishRegimeThatWithholdsEntries() {
        Regime r = MarketRegimeAnalyzer.analyze(falling(), falling(), cfg);

        assertEquals("BEARISH", r.regime());
        assertTrue(r.isBearish());
        assertFalse(r.allowsBuyNow(), "no entries are called in a bearish market");
        assertTrue(r.scoreMultiplier() < 1.0);
    }

    @Test
    void aMissingIndexIsNormalisedRatherThanHalvingTheScore() {
        Regime both = MarketRegimeAnalyzer.analyze(rising(), rising(), cfg);
        Regime one = MarketRegimeAnalyzer.analyze(null, rising(), cfg);

        assertEquals(both.regime(), one.regime(),
                "one index failing to fetch must not flip a bull market to neutral");
        assertEquals(both.maxScore(), one.maxScore());
        assertFalse(one.nifty50().known());
        assertTrue(one.nifty500().known());
    }

    @Test
    void noIndexDataAtAllIsNeutralAndNeverBullish() {
        Regime r = MarketRegimeAnalyzer.analyze(null, null, cfg);

        assertEquals("NEUTRAL", r.regime());
        assertFalse(r.isBullish(), "an unknown market must never be what unlocks an entry");
        assertTrue(r.summary().contains("No index data"), r.summary());
    }

    @Test
    void theRegimeThresholdsAreConfigurable() {
        BullishConfig demanding = new BullishConfig();
        demanding.setRegimeBullishScore(11);   // unreachable

        Regime r = MarketRegimeAnalyzer.analyze(rising(), rising(), demanding);

        assertNotEquals("BULLISH", r.regime());
    }

    @Test
    void disagreementBetweenTheIndicesIsSurfacedInTheSummary() {
        Regime r = MarketRegimeAnalyzer.analyze(rising(), falling(), cfg);

        assertTrue(r.nifty50().score() != r.nifty500().score());
        assertTrue(r.summary().contains("Large caps are leading")
                        || r.summary().contains("Breadth is better"),
                r.summary());
    }
}
