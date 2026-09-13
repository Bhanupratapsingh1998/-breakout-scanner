package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.bullish.BullishScoreEngine.BullishScore;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.IndexState;
import com.javawarriors.breakout.bullish.MarketRegimeAnalyzer.Regime;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BullishScoreEngineTest {

    private final BullishConfig cfg = new BullishConfig();

    private static Regime regime(String name, double multiplier) {
        IndexState unknown = IndexState.unknown("test");
        return new Regime(name, 8, 10, multiplier, !"BEARISH".equals(name), "test", unknown, unknown);
    }

    private BullishScore scoreOf(IndicatorSnapshot s, Regime r) {
        var trend = BullishTrendAnalyzer.analyze(s);
        var higher = BullishTrendAnalyzer.higherTimeframes(s);
        var momentum = MomentumAnalyzer.analyze(s);
        var rs = RelativeStrengthAnalyzer.analyze(s, null, null);
        var volume = VolumeAnalyzer.analyze(s, cfg);
        var pattern = ChartPatternDetector.detect(s, cfg);
        var breakout = BreakoutStageAnalyzer.analyze(s, pattern, cfg);
        var over = OverextensionAnalyzer.analyze(s, breakout, cfg);
        var plan = TradePlanCalculator.calculate(s, breakout, pattern, over, cfg);
        return BullishScoreEngine.score(s, trend, rs, momentum, volume, pattern, breakout, plan,
                higher, r, cfg);
    }

    @Test
    void theComponentsSumToTheRawTotalAndTheWeightsMatchTheSpec() {
        BullishScore sc = scoreOf(SeriesBuilder.healthyUptrend().snapshot(), regime("BULLISH", 1.0));

        double sum = sc.trend() + sc.relativeStrength() + sc.momentum() + sc.volume()
                + sc.priceStructure() + sc.patternQuality() + sc.breakoutQuality() + sc.riskReward();
        assertEquals(sc.rawTotal(), sum, 0.5, "the breakdown must actually add up to the total");

        assertEquals(20, BullishTrendAnalyzer.MAX_POINTS);
        assertEquals(15, RelativeStrengthAnalyzer.MAX_POINTS);
        assertEquals(10, MomentumAnalyzer.MAX_POINTS);
        assertEquals(10, VolumeAnalyzer.MAX_POINTS);
        assertEquals(10, BullishScoreEngine.PRICE_STRUCTURE_MAX);
        assertEquals(15, ChartPatternDetector.MAX_POINTS);
        assertEquals(10, BreakoutStageAnalyzer.MAX_POINTS);
        assertEquals(10, TradePlanCalculator.MAX_POINTS);

        double maxima = BullishTrendAnalyzer.MAX_POINTS + RelativeStrengthAnalyzer.MAX_POINTS
                + MomentumAnalyzer.MAX_POINTS + VolumeAnalyzer.MAX_POINTS
                + BullishScoreEngine.PRICE_STRUCTURE_MAX + ChartPatternDetector.MAX_POINTS
                + BreakoutStageAnalyzer.MAX_POINTS + TradePlanCalculator.MAX_POINTS;
        assertEquals(BullishScoreEngine.MAX_SCORE, maxima, "the eight weights must total 100");
    }

    @Test
    void theTotalStaysInsideZeroToOneHundred() {
        for (IndicatorSnapshot s : new IndicatorSnapshot[] {
                SeriesBuilder.healthyUptrend().snapshot(),
                SeriesBuilder.flatMarket(300).snapshot(),
                SeriesBuilder.startingAt(200).move(300, -50).snapshot() }) {
            BullishScore sc = scoreOf(s, regime("BULLISH", 1.0));
            assertTrue(sc.total() >= 0 && sc.total() <= 100, "got " + sc.total());
        }
    }

    @Test
    void anUptrendOutscoresADowntrendByAWideMargin() {
        BullishScore up = scoreOf(SeriesBuilder.healthyUptrend().snapshot(), regime("BULLISH", 1.0));
        BullishScore down = scoreOf(SeriesBuilder.startingAt(200).move(300, -50).snapshot(),
                regime("BULLISH", 1.0));

        assertTrue(up.total() > down.total() + 20,
                "expected a wide gap, got " + up.total() + " vs " + down.total());
        assertEquals("WEAK", down.classification());
    }

    @Test
    void theRegimeMultiplierIsAppliedToTheTotalNotToEachComponent() {
        IndicatorSnapshot s = SeriesBuilder.healthyUptrend().snapshot();

        BullishScore bull = scoreOf(s, regime("BULLISH", 1.0));
        BullishScore bear = scoreOf(s, regime("BEARISH", 0.90));

        assertEquals(bull.rawTotal(), bear.rawTotal(), 1e-9,
                "the raw breakdown must be identical - only the total is adjusted");
        assertEquals(bull.trend(), bear.trend());
        assertTrue(bear.total() < bull.total());
        assertEquals(bull.rawTotal() * 0.90, bear.total(), 0.1);
    }

    @Test
    void classificationBandsFollowSectionTwelve() {
        assertEquals("A+ BULLISH", BullishScoreEngine.classify(85, cfg));
        assertEquals("A+ BULLISH", BullishScoreEngine.classify(100, cfg));
        assertEquals("STRONG BULLISH", BullishScoreEngine.classify(84.9, cfg));
        assertEquals("STRONG BULLISH", BullishScoreEngine.classify(75, cfg));
        assertEquals("BULLISH WATCHLIST", BullishScoreEngine.classify(74.9, cfg));
        assertEquals("BULLISH WATCHLIST", BullishScoreEngine.classify(65, cfg));
        assertEquals("NEUTRAL / DEVELOPING", BullishScoreEngine.classify(64.9, cfg));
        assertEquals("NEUTRAL / DEVELOPING", BullishScoreEngine.classify(50, cfg));
        assertEquals("WEAK", BullishScoreEngine.classify(49.9, cfg));
        assertEquals("WEAK", BullishScoreEngine.classify(0, cfg));
    }

    @Test
    void classificationBandsAreConfigurable() {
        BullishConfig strict = new BullishConfig();
        strict.setScoreAPlus(95);

        assertEquals("STRONG BULLISH", BullishScoreEngine.classify(90, strict));
        assertEquals("A+ BULLISH", BullishScoreEngine.classify(96, strict));
    }

    @Test
    void theBreakdownIsCarriedThroughToTheApiShape() {
        BullishScore sc = scoreOf(SeriesBuilder.healthyUptrend().snapshot(), regime("BULLISH", 1.0));
        Map<String, Object> row = sc.toRow();

        @SuppressWarnings("unchecked")
        Map<String, Object> components = (Map<String, Object>) row.get("components");
        assertEquals(8, components.size(), "all eight components must be visible to the dashboard");
        assertTrue(components.containsKey("trend"));
        assertTrue(components.containsKey("riskReward"));
        assertEquals(sc.classification(), row.get("classification"));
    }

    @Test
    void aContractingRangeScoresHigherOnStructureThanAnExpandingOne() {
        IndicatorSnapshot tightening = SeriesBuilder.startingAt(100)
                .move(200, 30).chop(30, 4).chop(12, 0.8).snapshot();
        IndicatorSnapshot expanding = SeriesBuilder.startingAt(100)
                .move(200, 30).chop(30, 0.8).chop(12, 5).snapshot();

        double tight = BullishScoreEngine.volatilityContraction(tightening);
        double wide = BullishScoreEngine.volatilityContraction(expanding);

        assertTrue(tight < wide, "contraction " + tight + " should be below expansion " + wide);
    }
}
