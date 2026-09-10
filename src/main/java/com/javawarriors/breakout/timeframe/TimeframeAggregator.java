package com.javawarriors.breakout.timeframe;

import com.javawarriors.breakout.model.Bar;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

/**
 * Rolls daily OHLCV up into weekly and monthly bars.
 *
 * <p>Deliberately derived from the daily series the scan already fetched rather than asking Yahoo
 * for {@code interval=1wk} / {@code 1mo}: a second and third request per symbol would triple the
 * request count across a ~500 stock universe for data that is a pure function of what we already
 * hold.
 *
 * <p>The final period is intentionally left partial. A week that is only three days old still
 * describes where price is right now, and dropping it would make every Monday and every 1st of the
 * month analyse stale data.
 */
public final class TimeframeAggregator {

    /**
     * Yahoo timestamps the daily bar at the session open in UTC, which for NSE is 03:45Z. Bucketing
     * has to happen in exchange-local time or a Monday session lands in the previous week for any
     * viewer west of UTC.
     */
    private static final ZoneId MARKET_ZONE = ZoneId.of("Asia/Kolkata");

    private TimeframeAggregator() {
    }

    /** Calendar weeks, Monday-anchored (ISO). */
    public static List<Bar> toWeekly(List<Bar> daily) {
        return aggregate(daily, d -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
    }

    /** Calendar months, anchored on the 1st. */
    public static List<Bar> toMonthly(List<Bar> daily) {
        return aggregate(daily, d -> d.withDayOfMonth(1));
    }

    private interface PeriodKey {
        LocalDate of(LocalDate tradingDay);
    }

    /**
     * One output bar per period: open of the first session, close of the last, extremes across the
     * whole span, volume summed. The bar is timestamped with its first session so the series stays
     * chronological and lines up with the daily series it came from.
     */
    private static List<Bar> aggregate(List<Bar> daily, PeriodKey key) {
        List<Bar> out = new ArrayList<>();
        if (daily == null || daily.isEmpty()) return out;

        LocalDate currentKey = null;
        long openTime = 0;
        double open = 0, high = 0, low = 0, close = 0, volume = 0;

        for (Bar b : daily) {
            LocalDate day = Instant.ofEpochSecond(b.time()).atZone(MARKET_ZONE).toLocalDate();
            LocalDate periodKey = key.of(day);

            if (currentKey == null || !periodKey.equals(currentKey)) {
                if (currentKey != null) out.add(new Bar(openTime, open, high, low, close, volume));
                currentKey = periodKey;
                openTime = b.time();
                open = b.open();
                high = b.high();
                low = b.low();
                volume = 0;
            }
            high = Math.max(high, b.high());
            low = Math.min(low, b.low());
            close = b.close();
            volume += b.volume();
        }
        out.add(new Bar(openTime, open, high, low, close, volume));
        return out;
    }
}
