package com.javawarriors.breakout.timeframe;

import com.javawarriors.breakout.model.Bar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TrendAlignmentTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Weekday sessions walking `close` by `step` each day, starting at `start`. */
    private static List<Bar> series(int sessions, double start, double step) {
        List<Bar> bars = new ArrayList<>();
        LocalDate d = LocalDate.parse("2023-01-02");
        double c = start;
        while (bars.size() < sessions) {
            if (d.getDayOfWeek().getValue() < 6) {
                long t = d.atTime(LocalTime.of(9, 15)).atZone(IST).toEpochSecond();
                bars.add(new Bar(t, c, c * 1.01, c * 0.99, c, 1000));
                c += step;
            }
            d = d.plusDays(1);
        }
        return bars;
    }

    @Test
    void aSustainedAdvanceIsUpOnEveryTimeframe() {
        TrendAlignment.Alignment a = TrendAlignment.analyze(series(600, 100, 0.5));

        assertEquals("UP", a.daily().trend());
        assertEquals("UP", a.weekly().trend());
        assertEquals("UP", a.monthly().trend());
        assertEquals("ALIGNED_UP", a.verdict());
        assertTrue(a.supportsLong());
        assertEquals(3, a.upCount());
    }

    @Test
    void aSustainedDeclineIsDownOnEveryTimeframe() {
        TrendAlignment.Alignment a = TrendAlignment.analyze(series(600, 400, -0.5));

        assertEquals("DOWN", a.weekly().trend());
        assertEquals("DOWN", a.monthly().trend());
        assertEquals("COUNTER", a.verdict());
        assertFalse(a.supportsLong(), "a long has no business inside a falling weekly trend");
    }

    /**
     * The case the class exists for: a long decline with a sharp recent bounce. The daily series
     * turns up while the higher timeframes are still falling — a 10/10 daily breakout that is
     * really a bounce inside a downtrend.
     */
    @Test
    void aBounceInsideADowntrendReadsUpDailyButCounterOverall() {
        List<Bar> bars = new ArrayList<>(series(500, 400, -0.6));
        double last = bars.get(bars.size() - 1).close();
        LocalDate d = LocalDate.parse("2025-06-02");
        for (int i = 0; i < 15; i++) {
            last *= 1.03;
            long t = d.atTime(LocalTime.of(9, 15)).atZone(IST).toEpochSecond();
            bars.add(new Bar(t, last, last * 1.01, last * 0.99, last, 5000));
            d = d.plusDays(d.getDayOfWeek().getValue() == 5 ? 3 : 1);
        }

        TrendAlignment.Alignment a = TrendAlignment.analyze(bars);

        assertEquals("UP", a.daily().trend(), "the daily chart looks like a breakout");
        assertFalse(a.supportsLong(), "but a higher timeframe is still down, so it is a trap");
        assertEquals("COUNTER", a.verdict());

        // The bounce lifted price back over the weekly fast EMA, so the weekly is no longer a
        // clean DOWN -- structure, not price position, is what must still veto the long.
        assertNotEquals("DOWN", a.weekly().trend());
        assertTrue(a.weekly().structureBearish(), "weekly 20 EMA is still under its 50");
    }

    /** A violent bounce must not launder a downtrend into a tradeable setup. */
    @Test
    void evenALargeBounceDoesNotClearACounterVerdict() {
        List<Bar> bars = new ArrayList<>(series(500, 400, -0.6));
        double last = bars.get(bars.size() - 1).close();
        LocalDate d = LocalDate.parse("2025-06-02");
        for (int i = 0; i < 25; i++) {           // ~+109%
            last *= 1.03;
            long t = d.atTime(LocalTime.of(9, 15)).atZone(IST).toEpochSecond();
            bars.add(new Bar(t, last, last * 1.01, last * 0.99, last, 5000));
            d = d.plusDays(d.getDayOfWeek().getValue() == 5 ? 3 : 1);
        }

        TrendAlignment.Alignment a = TrendAlignment.analyze(bars);
        assertEquals("UP", a.daily().trend());
        assertEquals("COUNTER", a.verdict(), "a doubled price is still below a falling weekly 50 EMA");
        assertFalse(a.supportsLong());
    }

    @Test
    void tooLittleHistoryReportsUnknownRatherThanGuessing() {
        TrendAlignment.Alignment a = TrendAlignment.analyze(series(10, 100, 1));

        assertEquals("UNKNOWN", a.monthly().trend(), "10 sessions cannot describe a monthly trend");
        assertFalse(a.monthly().isKnown());
        assertFalse(a.monthly().toRow().containsKey("price"), "no numbers offered for an unknown read");
    }

    @Test
    void unknownHigherTimeframeDoesNotByItselfBlockALong() {
        TrendAlignment.Alignment a = TrendAlignment.analyze(series(10, 100, 1));
        assertTrue(a.supportsLong(), "absent evidence is not contrary evidence");
        assertNotEquals("COUNTER", a.verdict());
    }

    @Test
    void emptyInputIsUnknownEverywhereAndDoesNotThrow() {
        TrendAlignment.Alignment a = TrendAlignment.analyze(List.of());
        assertEquals("UNKNOWN", a.daily().trend());
        assertEquals("UNKNOWN", a.weekly().trend());
        assertEquals("UNKNOWN", a.monthly().trend());
        assertEquals(0, a.upCount());
    }
}
