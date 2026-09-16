package com.javawarriors.breakout.index500;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic daily series for the Index 500 tests.
 *
 * <p>Up days extend their high and barely undercut their open; down days do the reverse. That is
 * not cosmetic: every bar opens where the last one closed, so symmetric wicks would give the last
 * bar of a decline and the first bar of the rally after it an identical low, and a fractal pivot
 * needs strict inequality on both sides. Shaping the wick by direction puts each extreme on the bar
 * that actually made it, which is both what real bars do and what makes turns detectable.
 */
final class Index500TestSeries {

    private static final long DAY = 86_400L;
    private static final long START = 1_600_000_000L;

    private final List<Bar> bars = new ArrayList<>();
    private double price;

    private Index500TestSeries(double start) {
        this.price = start;
    }

    static Index500TestSeries startingAt(double price) {
        return new Index500TestSeries(price);
    }

    /** {@code days} bars covering {@code totalPct} in equal steps, at {@code volumeMultiple} volume. */
    Index500TestSeries move(int days, double totalPct, double volumeMultiple) {
        double perDay = Math.pow(1 + totalPct / 100.0, 1.0 / days) - 1;
        for (int i = 0; i < days; i++) {
            // A down day every fourth bar, so RSI has an average loss and never pins at 0 or 100 —
            // a fixture on that ceiling cannot demonstrate any RSI threshold gating anything.
            double step = i % 4 == 3 ? -Math.abs(perDay) * 0.4 : perDay;
            double open = price;
            price = price * (1 + step);
            add(open, price, volumeMultiple);
        }
        return this;
    }

    Index500TestSeries move(int days, double totalPct) {
        return move(days, totalPct, 1.0);
    }

    /** A tight range around the current price. */
    Index500TestSeries chop(int days, double amplitudePct, double volumeMultiple) {
        double anchor = price;
        for (int i = 0; i < days; i++) {
            double open = price;
            double phase = switch (i % 4) {
                case 0 -> amplitudePct / 2;
                case 1 -> amplitudePct;
                case 2 -> -amplitudePct / 2;
                default -> -amplitudePct;
            };
            price = anchor * (1 + phase / 100.0);
            add(open, price, volumeMultiple);
        }
        return this;
    }

    Index500TestSeries chop(int days, double amplitudePct) {
        return chop(days, amplitudePct, 1.0);
    }

    /** One decisive up bar closing on its high. */
    Index500TestSeries strongUpBar(double pct, double volumeMultiple) {
        double open = price;
        price = price * (1 + pct / 100.0);
        bars.add(new Bar(START + bars.size() * DAY, open, price, open * 0.998, price,
                100_000 * volumeMultiple));
        return this;
    }

    private void add(double open, double close, double volumeMultiple) {
        boolean up = close >= open;
        double hi = up ? close * 1.006 : open * 1.001;
        double lo = up ? open * 0.999 : close * 0.994;
        bars.add(new Bar(START + bars.size() * DAY, open, hi, lo, close, 100_000 * volumeMultiple));
    }

    List<Bar> build() {
        return List.copyOf(bars);
    }

    int size() {
        return bars.size();
    }

    /** ~300 bars that fall hard for six months, then base and turn up — the feature's target case. */
    static List<Bar> fellThenTurned() {
        return startingAt(200)
                .move(120, 10)            // a year ago it was fine
                .move(90, -35, 1.3)       // the decline
                .chop(40, 2, 0.7)         // basing, volume drying up
                .move(35, 22, 1.6)        // the turn, on expanding volume
                .build();
    }

    /** ~300 bars still falling at the end, with no base — the AVOID case. */
    static List<Bar> stillFalling() {
        return startingAt(200).move(120, 5).move(180, -55, 1.2).build();
    }

    /** ~300 bars of steady advance — a strong stock, not a decliner. */
    static List<Bar> steadyUptrend() {
        return startingAt(100).move(300, 70).build();
    }
}
