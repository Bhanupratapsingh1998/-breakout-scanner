package com.javawarriors.breakout.breakout;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How far price has run from its own mean — the one dimension of "too late" the checklist never
 * measured.
 *
 * <p>Gate J already limits how far above the BREAKOUT LEVEL price may sit, but that level moves up
 * with every new high, so a stock can clear a fresh 180-day resistance by 3% while being 40% above
 * its 200 EMA after a 60% run. The 3/5/10-day overextension flags do not catch it either: they are
 * volatility-scaled per window, so a steady grind higher never trips them even as the cumulative
 * move becomes enormous. Both measures answer "how sharp was the recent move"; neither answers
 * "how far from home are we".
 *
 * <p>Distance is expressed in ATRs rather than percent so the reading means the same thing on a
 * quiet large cap and a volatile small cap: 4 ATRs of stretch is 4 ATRs of stretch either way. The
 * percentage figures are carried alongside because they are what a human reads off a chart.
 */
public record StretchFromMean(String rating, double atrsAboveEma50, double pctAboveEma50,
                              double pctAboveEma200, double runUp63d) {

    /** Beyond this, price is far enough from the mean that entries pay for the gap. */
    private static final double EXTENDED_ATRS = 2.5;

    /** Beyond this, an entry is a chase: the move is already made and the mean is a long way down. */
    private static final double CHASE_ATRS = 4.0;

    /**
     * ATR distance alone is not sufficient. On a very quiet series the ATR shrinks until an
     * ordinary gap reads as many ATRs — a stock 4% above its 50 EMA measured 4.2 ATRs out in
     * testing, the same reading as one 19% above its mean after a 60% run. Requiring a real
     * percentage gap as well keeps the rating meaning "price has actually travelled", which is the
     * only thing that makes an entry late.
     */
    private static final double EXTENDED_MIN_PCT = 6.0;
    private static final double CHASE_MIN_PCT = 10.0;

    public static StretchFromMean unknown() {
        return new StretchFromMean("UNKNOWN", Double.NaN, Double.NaN, Double.NaN, Double.NaN);
    }

    public boolean isKnown() {
        return !"UNKNOWN".equals(rating);
    }

    /** Too far from the mean to initiate: the setup may be fine, the price is not. */
    public boolean isChase() {
        return "CHASE".equals(rating);
    }

    /**
     * @param price   latest close
     * @param ema50   the swing-trading anchor the stretch is measured against
     * @param ema200  long-term mean, reported for context only
     * @param atr     ATR(14) at the same bar; non-positive or NaN makes the reading UNKNOWN
     * @param close63 close 63 sessions (~3 months) ago, or NaN when history is too short
     */
    public static StretchFromMean analyze(double price, double ema50, double ema200,
                                          double atr, double close63) {
        if (Double.isNaN(price) || Double.isNaN(ema50) || Double.isNaN(atr) || !(atr > 0)) {
            return unknown();
        }

        double atrsAbove = (price - ema50) / atr;
        double pct50 = (price / ema50 - 1) * 100;
        double pct200 = (Double.isNaN(ema200) || ema200 <= 0) ? Double.NaN : (price / ema200 - 1) * 100;
        double runUp = (Double.isNaN(close63) || close63 <= 0) ? Double.NaN : (price / close63 - 1) * 100;

        // Both readings must agree. Below the mean is never a chase either: a stock trading under
        // its 50 EMA has not run anywhere, whatever the ATR arithmetic says.
        String rating;
        if (atrsAbove >= CHASE_ATRS && pct50 >= CHASE_MIN_PCT) rating = "CHASE";
        else if (atrsAbove >= EXTENDED_ATRS && pct50 >= EXTENDED_MIN_PCT) rating = "EXTENDED";
        else rating = "NORMAL";

        return new StretchFromMean(rating, atrsAbove, pct50, pct200, runUp);
    }

    public Map<String, Object> toRow() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rating", rating);
        if (isKnown()) {
            row.put("atrsAboveEma50", atrsAboveEma50);
            row.put("pctAboveEma50", pctAboveEma50);
            if (!Double.isNaN(pctAboveEma200)) row.put("pctAboveEma200", pctAboveEma200);
            if (!Double.isNaN(runUp63d)) row.put("runUp63dPct", runUp63d);
        }
        return row;
    }
}
