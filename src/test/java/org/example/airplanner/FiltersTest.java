package org.example.airplanner;

import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.util.Dates;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiltersTest {

    @Test
    void parsesExplicitTimeWindow() {
        TimeWindow window = TimeWindow.parse("07:30-12:00");
        assertTrue(window.contains(LocalTime.of(7, 30)));
        assertTrue(window.contains(LocalTime.of(12, 0)));
        assertFalse(window.contains(LocalTime.of(7, 29)));
        assertFalse(window.contains(LocalTime.of(12, 1)));
        assertEquals("07:30", window.apiFrom());
        assertEquals("12:00", window.apiTo());
    }

    @Test
    void parsesNamedTimeWindows() {
        assertTrue(TimeWindow.parse("evening").contains(LocalTime.of(21, 0)));
        assertFalse(TimeWindow.parse("evening").contains(LocalTime.of(17, 59)));
        assertTrue(TimeWindow.parse("any").isAny());
        assertTrue(TimeWindow.parse(null).isAny());
    }

    @Test
    void rejectsBackwardsTimeWindow() {
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("18:00-09:00"));
        assertThrows(IllegalArgumentException.class, () -> TimeWindow.parse("lunchtime"));
    }

    @Test
    void parsesDaySelections() {
        DayFilter weekend = DayFilter.parse("weekend");
        assertTrue(weekend.allows(DayOfWeek.SATURDAY));
        assertFalse(weekend.allows(DayOfWeek.MONDAY));

        DayFilter friSat = DayFilter.parse("fri,sat");
        assertEquals("FRIDAY,SATURDAY", friSat.apiValue());
        assertFalse(friSat.isAny());

        assertTrue(DayFilter.parse("any").isAny());
        assertTrue(DayFilter.parse(null).isAny());
        assertThrows(IllegalArgumentException.class, () -> DayFilter.parse("funday"));
    }

    @Test
    void parsesDates() {
        assertEquals(LocalDate.of(2026, 10, 15), Dates.parse("2026-10-15", null));
        assertEquals(LocalDate.of(2026, 10, 1), Dates.parse("2026-10", null));
        assertEquals(LocalDate.now().plusDays(1), Dates.parse("tomorrow", null));
        assertEquals(LocalDate.now().plusDays(10), Dates.parse("+10d", null));
        assertEquals(LocalDate.now().plusWeeks(3), Dates.parse("3w", null));
        assertEquals(YearMonth.of(2026, 11), Dates.parseMonth("2026-11"));
        assertThrows(IllegalArgumentException.class, () -> Dates.parse("next thursday", null));
    }

    @Test
    void parsesDurationsAndRanges() {
        assertEquals(Duration.ofHours(36), Dates.parseDuration("36h"));
        assertEquals(Duration.ofMinutes(90), Dates.parseDuration("90m"));
        assertEquals(Duration.ofDays(2), Dates.parseDuration("2d"));

        assertArrayEqualsInt(new int[]{2, 5}, Dates.parseRange("2-5", null));
        assertArrayEqualsInt(new int[]{3, 3}, Dates.parseRange("3", null));
        assertArrayEqualsInt(new int[]{1, 7}, Dates.parseRange(null, new int[]{1, 7}));
    }

    private static void assertArrayEqualsInt(int[] expected, int[] actual) {
        assertEquals(expected[0], actual[0]);
        assertEquals(expected[1], actual[1]);
    }
}
