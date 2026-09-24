package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixture builder for the wick tests.
 *
 * <p>Bars are placed by hand rather than generated, because every threshold this feature has is a
 * ratio between a body, a wick and a range. A random walk that happens to produce a hammer proves
 * nothing about where the boundary is; a bar written out in full can be checked against the
 * arithmetic in the test that uses it.
 */
final class WickTestSeries {

    private static final long MINUTE = 60L;
    private static final long START = 1_600_000_000L;

    private final List<Bar> bars = new ArrayList<>();
    private double price;

    private WickTestSeries(double start) {
        this.price = start;
    }

    static WickTestSeries startingAt(double price) {
        return new WickTestSeries(price);
    }

    /** {@code n} quiet candles drifting by {@code perBar} percent, with small symmetric wicks. */
    WickTestSeries drift(int n, double perBar) {
        for (int i = 0; i < n; i++) {
            double open = price;
            price = price * (1 + perBar / 100.0);
            double hi = Math.max(open, price) * 1.001;
            double lo = Math.min(open, price) * 0.999;
            bars.add(new Bar(time(), open, hi, lo, price, 100_000));
        }
        return this;
    }

    /** One candle, spelled out. */
    WickTestSeries bar(double open, double high, double low, double close, double volume) {
        bars.add(new Bar(time(), open, high, low, close, volume));
        price = close;
        return this;
    }

    /**
     * The setup itself: a decisive down candle, then an up candle that takes it back.
     *
     * <p>Merged these become open 100, high 100.2, low 95.0, close 99.8 - a 5.2 range holding a
     * 0.2 body and a 4.8 lower wick, which is a wick 24x the body and a close 92% of the way up.
     */
    WickTestSeries rejectionPair(double volume) {
        double open = price;
        double drop = open * 0.96;
        bar(open, open * 1.002, drop * 0.998, drop, volume);
        bar(drop, open * 1.000, drop * 0.99, open * 0.998, volume);
        return this;
    }

    /**
     * A three-candle version: sellers press for two candles, then one takes it all back.
     *
     * <p>Merged these are open 100, high 100.2, low 94.0, close 99.8 - a wick 29x the body. Note
     * that no adjacent <em>pair</em> inside it qualifies: the first two candles are both down, and
     * the last two merge into a body far too fat for a hammer. It is only a signal at three.
     */
    WickTestSeries slowRejectionTriple(double volume) {
        double open = price;
        double mid = open * 0.975;
        double bottom = open * 0.95;
        bar(open, open * 1.002, mid * 0.999, mid, volume);
        bar(mid, mid * 1.001, bottom * 0.99, bottom, volume);
        bar(bottom, open * 1.000, bottom * 0.999, open * 0.998, volume);
        return this;
    }

    /**
     * The bearish mirror: a decisive up candle, then a down candle that sells it all back.
     *
     * <p>Every number is the {@link #rejectionPair} reflected about the open, so the two directions
     * are tested against the same geometry rather than two fixtures that happen to differ.
     */
    WickTestSeries rejectionPairBearish(double volume) {
        double open = price;
        double pop = open * 1.04;
        bar(open, pop * 1.002, open * 0.998, pop, volume);
        bar(pop, pop * 1.01, open * 1.000, open * 1.002, volume);
        return this;
    }

    /** Two up candles that still merge into a long lower wick - the shape without the story. */
    WickTestSeries twoGreenPair() {
        double open = price;
        bar(open * 0.96, open * 0.97, open * 0.95, open * 0.965, 100_000);
        bar(open * 0.965, open * 1.000, open * 0.964, open * 0.998, 100_000);
        return this;
    }

    List<Bar> build() {
        return List.copyOf(bars);
    }

    double lastClose() {
        return bars.get(bars.size() - 1).close();
    }

    private long time() {
        return START + bars.size() * MINUTE * 15;
    }

    /** ~50 candles sliding down into the rejection pair - the case the feature exists to find. */
    static List<Bar> declineThenRejection() {
        return startingAt(120).drift(48, -0.35).rejectionPair(250_000).build();
    }

    /** ~50 candles sliding down into a three-candle rejection. */
    static List<Bar> declineThenSlowRejection() {
        return startingAt(120).drift(47, -0.35).slowRejectionTriple(250_000).build();
    }

    /** The same pair, but after a rally. A long lower wick here is a Hanging Man, not a Hammer. */
    static List<Bar> rallyThenSameShape() {
        return startingAt(80).drift(48, 0.35).rejectionPair(250_000).build();
    }

    /** ~50 candles climbing into a bearish rejection - the mirror of the feature's target case. */
    static List<Bar> rallyThenBearishRejection() {
        return startingAt(80).drift(48, 0.35).rejectionPairBearish(250_000).build();
    }

    /** A bearish-shaped pair after a decline: a bounce sold back, not a top. */
    static List<Bar> declineThenBearishShape() {
        return startingAt(120).drift(48, -0.35).rejectionPairBearish(250_000).build();
    }
}
