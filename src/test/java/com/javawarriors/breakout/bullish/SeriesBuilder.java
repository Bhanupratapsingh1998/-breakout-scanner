package com.javawarriors.breakout.bullish;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds deterministic OHLCV series for the bullish-engine tests.
 *
 * <p>Every test in this package works off hand-shaped price paths rather than recorded market data.
 * That is a deliberate trade: recorded data proves the code runs, but only a constructed series can
 * prove the code detected the thing it claims to - a test asserting "this is a bull flag" is only
 * meaningful if the series provably contains one and nothing else.
 *
 * <p>There is no randomness anywhere here, so a failure is always reproducible.
 */
final class SeriesBuilder {

    /** Trading-day spacing in epoch seconds; the aggregator only needs monotonic timestamps. */
    private static final long DAY = 86_400L;
    private static final long START = 1_600_000_000L;

    private final List<Bar> bars = new ArrayList<>();
    private double price;
    private double volume;

    private SeriesBuilder(double startPrice, double baseVolume) {
        this.price = startPrice;
        this.volume = baseVolume;
    }

    static SeriesBuilder startingAt(double price) {
        return new SeriesBuilder(price, 100_000);
    }

    static SeriesBuilder startingAt(double price, double baseVolume) {
        return new SeriesBuilder(price, baseVolume);
    }

    /** Adds {@code days} bars moving {@code totalPct} in total, in equal daily steps. */
    SeriesBuilder move(int days, double totalPct, double volumeMultiple) {
        double perDay = Math.pow(1 + totalPct / 100.0, 1.0 / days) - 1;
        for (int i = 0; i < days; i++) {
            double open = price;
            price = price * (1 + perDay);
            addBar(open, price, volumeMultiple);
        }
        return this;
    }

    SeriesBuilder move(int days, double totalPct) {
        return move(days, totalPct, 1.0);
    }

    /** Flat drift with a small fixed oscillation, so the range is non-zero but tight. */
    SeriesBuilder chop(int days, double amplitudePct, double volumeMultiple) {
        double anchor = price;
        for (int i = 0; i < days; i++) {
            double open = price;
            // A fixed 4-bar cycle: up, up, down, down. Deterministic and visibly range-bound.
            double phase = switch (i % 4) {
                case 0 -> amplitudePct / 2;
                case 1 -> amplitudePct;
                case 2 -> -amplitudePct / 2;
                default -> -amplitudePct;
            };
            price = anchor * (1 + phase / 100.0);
            addBar(open, price, volumeMultiple);
        }
        return this;
    }

    SeriesBuilder chop(int days, double amplitudePct) {
        return chop(days, amplitudePct, 1.0);
    }

    /** One bar closing at the top of its range - a strong breakout candle. */
    SeriesBuilder strongUpBar(double pct, double volumeMultiple) {
        double open = price;
        price = price * (1 + pct / 100.0);
        long time = START + bars.size() * DAY;
        bars.add(new Bar(time, open, price, Math.min(open, price) * 0.998, price,
                volume * volumeMultiple));
        return this;
    }

    /** One bar that spiked then gave it all back - the weak-spike case the spec warns about. */
    SeriesBuilder upperWickBar(double spikePct, double volumeMultiple) {
        double open = price;
        double high = open * (1 + spikePct / 100.0);
        price = open * 1.001;
        long time = START + bars.size() * DAY;
        bars.add(new Bar(time, open, high, open * 0.999, price, volume * volumeMultiple));
        return this;
    }

    /**
     * Up days extend their high and barely undercut their open; down days do the reverse.
     *
     * <p>Not cosmetic. Each bar opens exactly where the previous one closed, so a symmetric wick
     * would give the last bar of a decline and the first bar of the rally after it an identical
     * low - and a fractal pivot needs strict inequality on both sides, so the turn would not
     * register as a swing low at all. Shaping the wick by direction puts the extreme on the bar
     * that actually made it, which is both what real bars do and what makes the turns detectable.
     */
    private void addBar(double open, double close, double volumeMultiple) {
        boolean up = close >= open;
        double hi = up ? close * 1.006 : open * 1.001;
        double lo = up ? open * 0.999 : close * 0.994;
        long time = START + bars.size() * DAY;
        bars.add(new Bar(time, open, hi, lo, close, volume * volumeMultiple));
    }

    List<Bar> build() {
        return List.copyOf(bars);
    }

    IndicatorSnapshot snapshot() {
        return IndicatorSnapshot.of("TEST", build());
    }

    int size() {
        return bars.size();
    }

    double lastClose() {
        return price;
    }

    /**
     * A long, healthy uptrend with enough history for EMA200 and the 6-month return. Used as the
     * "everything is fine" baseline that individual tests then perturb.
     */
    static SeriesBuilder healthyUptrend() {
        // Advances separated by rests, and finishing on a rest. An unbroken run of up days would
        // be simpler to write but produces RSI 100 and zero down-day volume, which no real stock
        // has — tests built on it would be asserting against an artifact.
        return startingAt(100)
                .move(90, 25)      // steady advance, builds the EMA200 base
                .chop(20, 2)
                .move(70, 20)
                .chop(20, 2)
                .move(60, 18)
                .chop(13, 2);   // ends on an up phase of the cycle, so price sits above the 20 EMA
    }

    /** A flat, featureless series - the "no pattern, no trend" control case. */
    static SeriesBuilder flatMarket(int days) {
        return startingAt(100).chop(days, 1.5);
    }
}
