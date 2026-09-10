package com.javawarriors.breakout.timeframe;

import com.javawarriors.breakout.model.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TimeframeAggregatorTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Mirrors Yahoo: the daily bar is stamped at the 09:15 IST session open. */
    private static Bar bar(String isoDate, double o, double h, double l, double c, double v) {
        long t = LocalDate.parse(isoDate).atTime(LocalTime.of(9, 15)).atZone(IST).toEpochSecond();
        return new Bar(t, o, h, l, c, v);
    }

    @Test
    void weeklyBarTakesFirstOpenLastCloseExtremesAndSummedVolume() {
        // Mon-Fri of one ISO week.
        List<Bar> daily = List.of(
                bar("2026-09-07", 100, 105, 99, 104, 1000),
                bar("2026-09-08", 104, 110, 103, 106, 2000),
                bar("2026-09-09", 106, 108,  95, 97,  1500),
                bar("2026-09-10", 97,  102, 96, 101, 2500),
                bar("2026-09-11", 101, 112, 100, 111, 3000));

        List<Bar> weekly = TimeframeAggregator.toWeekly(daily);

        assertEquals(1, weekly.size(), "one calendar week collapses to one bar");
        Bar w = weekly.get(0);
        assertEquals(100, w.open(),  1e-9, "open of the first session");
        assertEquals(112, w.high(),  1e-9, "highest high across the week");
        assertEquals(95,  w.low(),   1e-9, "lowest low across the week");
        assertEquals(111, w.close(), 1e-9, "close of the last session");
        assertEquals(10000, w.volume(), 1e-9, "volume is summed, not averaged");
        assertEquals(daily.get(0).time(), w.time(), "stamped with its first session");
    }

    @Test
    void weeksSplitOnMondayNotOnAnArbitrarySevenDayStride() {
        // Fri + Mon: adjacent sessions, different ISO weeks.
        List<Bar> daily = List.of(
                bar("2026-09-11", 100, 101, 99, 100, 10),
                bar("2026-09-14", 100, 120, 100, 118, 20));

        List<Bar> weekly = TimeframeAggregator.toWeekly(daily);

        assertEquals(2, weekly.size(), "a Friday and the following Monday are different weeks");
        assertEquals(100, weekly.get(0).close(), 1e-9);
        assertEquals(118, weekly.get(1).close(), 1e-9);
    }

    @Test
    void weekendGapDoesNotStartANewWeekMidWeek() {
        // A holiday-shortened week: Tue, Wed, Fri only. Still one weekly bar.
        List<Bar> daily = List.of(
                bar("2026-09-08", 100, 106, 99, 105, 10),
                bar("2026-09-09", 105, 107, 104, 106, 10),
                bar("2026-09-11", 106, 109, 105, 108, 10));

        assertEquals(1, TimeframeAggregator.toWeekly(daily).size(),
                "missing sessions inside a week must not split it");
    }

    @Test
    void monthlyBarsSplitOnCalendarMonthBoundary() {
        List<Bar> daily = List.of(
                bar("2026-08-28", 50, 55, 49, 54, 100),
                bar("2026-08-31", 54, 60, 53, 58, 100),
                bar("2026-09-01", 58, 61, 57, 59, 100),
                bar("2026-09-30", 59, 70, 58, 68, 100));

        List<Bar> monthly = TimeframeAggregator.toMonthly(daily);

        assertEquals(2, monthly.size());
        assertEquals(50, monthly.get(0).open(),  1e-9);
        assertEquals(58, monthly.get(0).close(), 1e-9);
        assertEquals(60, monthly.get(0).high(),  1e-9);
        assertEquals(58, monthly.get(1).open(),  1e-9);
        assertEquals(68, monthly.get(1).close(), 1e-9);
        assertEquals(70, monthly.get(1).high(),  1e-9);
    }

    @Test
    void trailingPartialPeriodIsKeptSoTheCurrentWeekIsVisible() {
        // Mon + Tue only: the week is still forming.
        List<Bar> daily = List.of(
                bar("2026-09-07", 100, 105, 99, 104, 10),
                bar("2026-09-08", 104, 110, 103, 109, 10));

        List<Bar> weekly = TimeframeAggregator.toWeekly(daily);

        assertEquals(1, weekly.size(), "an in-progress week must not be dropped");
        assertEquals(109, weekly.get(0).close(), 1e-9, "carries the latest close");
    }

    @Test
    void emptyAndSingleBarInputsAreHandled() {
        assertTrue(TimeframeAggregator.toWeekly(List.of()).isEmpty());
        assertTrue(TimeframeAggregator.toMonthly(null).isEmpty());
        assertEquals(1, TimeframeAggregator.toWeekly(List.of(bar("2026-09-09", 1, 2, 0.5, 1.5, 5))).size());
    }

    @Test
    void aggregationConservesTotalVolumeAcrossAYearOfSessions() {
        List<Bar> daily = new ArrayList<>();
        LocalDate d = LocalDate.parse("2025-01-01");
        double total = 0;
        for (int i = 0; i < 365; i++, d = d.plusDays(1)) {
            if (d.getDayOfWeek().getValue() >= 6) continue; // skip weekends
            daily.add(bar(d.toString(), 10, 11, 9, 10, 100));
            total += 100;
        }
        double weeklyTotal = TimeframeAggregator.toWeekly(daily).stream().mapToDouble(Bar::volume).sum();
        double monthlyTotal = TimeframeAggregator.toMonthly(daily).stream().mapToDouble(Bar::volume).sum();

        assertEquals(total, weeklyTotal, 1e-6, "no session may be dropped or double counted");
        assertEquals(total, monthlyTotal, 1e-6);
        assertEquals(12, TimeframeAggregator.toMonthly(daily).size(), "a full year is 12 monthly bars");
    }
}
