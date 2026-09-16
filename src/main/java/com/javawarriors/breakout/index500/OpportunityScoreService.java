package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.bullish.IndicatorSnapshot;
import com.javawarriors.breakout.bullish.PriceStructure;
import com.javawarriors.breakout.index500.pattern.PatternResult;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Turns one stock's readings into the 0-100 opportunity score.
 *
 * <p>The single most important judgement in this class is what a large decline is worth. A stock
 * being down 40% is <em>not</em> bullish - it is a reason to look, not a reason to buy - so decline
 * carries 15 of 100, and it is scored as a band that <b>peaks and then falls away</b>: a 25-35%
 * drop is the interesting zone, while a 70% collapse scores less than it, because at that point the
 * market is usually pricing something this screen cannot see.
 *
 * <p>The 20 points that actually decide a high score belong to the pattern, which is evidence the
 * decline has stopped. That ordering is what stops the whole feature degenerating into a top-losers
 * list.
 */
@Service
public class OpportunityScoreService {

    public OpportunityScore score(IndicatorSnapshot s, List<PatternResult> patterns,
                                  PatternResult best, double benchmark6mPct, Index500Config cfg) {
        return OpportunityScore.of(
                declinePoints(pct(s.return6m)),
                patternPoints(patterns, best),
                structurePoints(s),
                volumePoints(s),
                rsiPoints(s),
                adxPoints(s),
                emaPoints(s),
                relativeStrengthPoints(pct(s.return6m), benchmark6mPct));
    }

    /**
     * 15 points for having fallen - but as a band, not a ladder.
     *
     * <p>Peaks in the 20-40% range. Below that there is no dislocation to recover from; far above
     * it, the size of the fall is usually information about the business rather than an opportunity,
     * and rewarding it linearly would put the most broken stocks at the top of the list.
     */
    static double declinePoints(double return6mPct) {
        if (Double.isNaN(return6mPct)) return 0;
        double drop = -return6mPct;                 // positive when the stock fell
        if (drop < 5) return 2;                     // barely moved
        if (drop < 12) return 6;
        if (drop < 20) return 11;
        if (drop <= 40) return 15;                  // the zone this screen is looking for
        if (drop <= 55) return 10;
        return 5;                                   // a collapse, not a dip
    }

    /** 20 points: the best pattern's confidence, with a little credit for corroboration. */
    static double patternPoints(List<PatternResult> patterns, PatternResult best) {
        if (best == null) return 0;
        double points = best.confidence() / 10.0 * 16;          // up to 16 from the strongest
        if (PatternResult.CONFIRMED.equals(best.confirmation())) points += 2.5;
        if (patterns.size() >= 3) points += 1.5;                // several detectors agree
        else if (patterns.size() == 2) points += 0.8;
        return Math.min(OpportunityScore.MAX_PATTERN, points);
    }

    /** 15 points: what price action itself is doing, independent of the averages. */
    static double structurePoints(IndicatorSnapshot s) {
        double points = switch (PriceStructure.classify(s)) {
            case "HH+HL" -> 9;
            case "LH+LL" -> 0;
            default -> 4;
        };
        double fromLow = s.lowestLow(IndicatorSnapshot.BARS_52W);
        if (fromLow > 0) {
            double aboveLow = (s.price / fromLow - 1) * 100;
            // Off the floor, but not so far that the low is ancient history.
            if (aboveLow >= 8 && aboveLow <= 40) points += 4;
            else if (aboveLow > 40) points += 2;
        }
        if (s.price > s.prevClose) points += 2;
        return Math.min(OpportunityScore.MAX_STRUCTURE, points);
    }

    /** 10 points: is anyone actually participating in the turn. */
    static double volumePoints(IndicatorSnapshot s) {
        double points = 0;
        if (s.volumeRatio >= 2.0) points += 6;
        else if (s.volumeRatio >= 1.5) points += 5;
        else if (s.volumeRatio >= 1.2) points += 3.5;
        else if (s.volumeRatio >= 0.9) points += 2;
        // A rising volume floor is accumulation; a single spike can be one order.
        if (s.avgVolume50 > 0 && s.avgVolume20 / s.avgVolume50 >= 1.1) points += 4;
        else if (s.avgVolume50 > 0 && s.avgVolume20 / s.avgVolume50 >= 0.95) points += 2;
        return Math.min(OpportunityScore.MAX_VOLUME, points);
    }

    /**
     * 10 points: RSI recovering out of oversold, not already spent.
     *
     * <p>Peaks in the 45-60 band. Deep oversold scores low on purpose - it means the fall is still
     * in progress - and so does overbought, which on a fallen stock means the bounce is late.
     */
    static double rsiPoints(IndicatorSnapshot s) {
        double rsi = s.lastRsi;
        if (Double.isNaN(rsi)) return 0;
        if (rsi >= 45 && rsi <= 60) return 10;
        if (rsi > 60 && rsi <= 68) return 7;
        if (rsi >= 38 && rsi < 45) return 6.5;
        if (rsi >= 30 && rsi < 38) return 4;
        if (rsi > 68 && rsi <= 75) return 3;
        return 1;
    }

    /** 10 points: ADX, read as "has the downtrend lost its grip". */
    static double adxPoints(IndicatorSnapshot s) {
        double adx = s.lastAdx;
        if (Double.isNaN(adx)) return 0;
        boolean rising = s.price > s.lastEma20;
        if (adx >= 25) return rising ? 10 : 3;      // a strong trend, good only if it is now upward
        if (adx >= 20) return rising ? 8 : 4;
        if (adx >= 15) return 6;                    // the downtrend has faded - the usual base case
        return 4;
    }

    /** 10 points: where price sits against its own averages. */
    static double emaPoints(IndicatorSnapshot s) {
        double points = 0;
        if (s.price > s.lastEma20) points += 3;
        if (s.price > s.lastEma50) points += 3;
        if (s.price > s.lastEma200) points += 2;
        if (s.lastEma20 > s.lastEma50) points += 2;
        return Math.min(OpportunityScore.MAX_EMA, points);
    }

    /**
     * 10 points: how the stock's own six months compares with the market's.
     *
     * <p>Inverted against the rest of this screen on purpose. Everywhere else, falling less than the
     * market is better; here a stock that fell <em>with</em> its market rather than because of
     * something specific to it is the more interesting recovery candidate, so mild
     * underperformance scores well and catastrophic underperformance does not.
     */
    static double relativeStrengthPoints(double return6mPct, double benchmark6mPct) {
        if (Double.isNaN(return6mPct) || Double.isNaN(benchmark6mPct)) return 5;
        double excess = return6mPct - benchmark6mPct;
        if (excess >= 5) return 10;                 // held up better than the market
        if (excess >= 0) return 8.5;
        if (excess >= -10) return 7;                // fell roughly with the market
        if (excess >= -25) return 4;
        return 1.5;                                 // fell far harder than the market did
    }

    private static double pct(double fraction) {
        return Double.isNaN(fraction) ? Double.NaN : fraction * 100;
    }
}
