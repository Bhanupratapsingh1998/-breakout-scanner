package com.javawarriors.breakout.wick;

import com.javawarriors.breakout.model.Bar;

import java.util.ArrayList;
import java.util.List;

/**
 * Merges each candle with the ones before it, which is the whole idea behind this feature.
 *
 * <p>Two 15-minute candles - a drop and the recovery that takes it back - are one 30-minute candle
 * with a long lower wick. Three of them are one 45-minute candle, which is the same story told over
 * a slower fall: sellers press for two candles rather than one, and buyers still take it all back.
 * The fight is invisible on the lower timeframe and obvious on the higher one, and that is the
 * observation the scan is built on.
 *
 * <h2>Why rolling rather than a fixed grid</h2>
 *
 * <p>A real 30-minute chart has fixed boundaries, so it only ever pairs candles 0-1, 2-3, 4-5. A
 * drop-and-recovery that straddles a boundary - candles 1-2 - is split down the middle and shows up
 * as nothing. Grouping every candle with its predecessors is a superset of every possible
 * alignment, so the shape is found wherever it forms rather than only where the clock agrees.
 *
 * <h2>The property that makes context tests safe</h2>
 *
 * <p>{@code merged[i]} always ends on {@code base[i]}, so {@code merged[i].close() ==
 * base[i].close()} for every i and every group size. Closes are untouched by the merge, and any
 * test written against closes - the prior-trend check inside the hammer detector, for one - asks
 * exactly the same question of the merged series that it would of the base series. Only the opens,
 * highs and lows widen, which is precisely what a bigger candle is.
 */
public final class CandleMerger {

    private CandleMerger() {
    }

    /**
     * The rolling {@code count}-candle series, the same length as the input so that every index
     * stays directly comparable with the base series.
     *
     * <p>The first {@code count - 1} entries have fewer candles behind them than asked for. They
     * are returned rather than omitted - dropping them would shift every index - and the detector
     * skips them, because a group that is not the size it claims cannot be judged against
     * thresholds set for that size.
     */
    public static List<Bar> rolling(List<Bar> base, int count) {
        List<Bar> out = new ArrayList<>(base.size());
        for (int i = 0; i < base.size(); i++) {
            out.add(mergeRange(base, Math.max(0, i - count + 1), i));
        }
        return out;
    }

    /** One candle spanning {@code base[from..to]} inclusive. */
    public static Bar mergeRange(List<Bar> base, int from, int to) {
        Bar first = base.get(from);
        double high = first.high();
        double low = first.low();
        double volume = 0;
        for (int k = from; k <= to; k++) {
            Bar b = base.get(k);
            high = Math.max(high, b.high());
            low = Math.min(low, b.low());
            volume += b.volume();
        }
        return new Bar(first.time(), first.open(), high, low, base.get(to).close(), volume);
    }

    /** One candle spanning both: the first one's open, the last one's close, the extremes of each. */
    public static Bar merge(Bar first, Bar second) {
        return mergeRange(List.of(first, second), 0, 1);
    }
}
