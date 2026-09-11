package org.example.airplanner.web;

import org.example.airplanner.model.Airport;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.SortBy;
import org.example.airplanner.search.AirportDirectory;
import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The form is the whole contract of the web UI, so it gets its own tests. */
class SearchFormTest {

    private final AirportDirectory directory = new AirportDirectory(List.of(
            new Airport("DUB", "Dublin", "Dublin", "Leinster", "Ireland", "ie", 53.4, -6.2, "Europe/Dublin", true),
            new Airport("STN", "London Stansted", "London", "England", "United Kingdom", "gb", 51.9, 0.2, "Europe/London", true),
            new Airport("BCN", "Barcelona", "Barcelona", "Costa Brava", "Spain", "es", 41.3, 2.1, "Europe/Madrid", true)));

    private SearchForm form() {
        SearchForm form = new SearchForm();
        form.setFrom("DUB");
        form.setTo("STN");
        form.setDepartFrom("2026-11-01");
        form.setDepartTo("2026-11-30");
        return form;
    }

    @Test
    void anEmptyOriginIsNotASearch() {
        assertFalse(new SearchForm().hasQuery());
        assertTrue(form().hasQuery());
    }

    @Test
    void mapsTheFormOntoSearchCriteria() {
        SearchForm form = form();
        form.setOutDays(List.of("FRIDAY", "SATURDAY"));
        form.setBackDays(List.of("SUNDAY"));
        form.setOutTimeFrom("15:00");
        form.setOutTimeTo("23:59");
        form.setBackTimeFrom("06:00");
        form.setBackTimeTo("11:59");
        form.setMinNights(2);
        form.setMaxNights(3);
        form.setBudget("120");
        form.setMinStay("36h");
        form.setPassengers(2);
        form.setSort(SortBy.VALUE);

        SearchCriteria criteria = form.toCriteria(directory);

        assertEquals(List.of("DUB"), criteria.origins());
        assertEquals(List.of("STN"), criteria.destinations());
        assertEquals(LocalDate.of(2026, 11, 1), criteria.departFrom());
        assertEquals(LocalDate.of(2026, 11, 30), criteria.departTo());
        assertTrue(criteria.outboundDays().allows(DayOfWeek.SATURDAY));
        assertFalse(criteria.outboundDays().allows(DayOfWeek.SUNDAY));
        assertTrue(criteria.inboundDays().allows(DayOfWeek.SUNDAY));
        assertEquals(LocalTime.of(15, 0), criteria.outboundTime().from());
        assertEquals(LocalTime.of(11, 59), criteria.inboundTime().to());
        assertEquals(2, criteria.minNights());
        assertEquals(3, criteria.maxNights());
        assertEquals("120.00 EUR", criteria.maxPrice().toString());
        assertEquals(Duration.ofHours(36), criteria.minTimeAtDestination());
        assertEquals(2, criteria.passengers());
        assertEquals(SortBy.VALUE, criteria.sort());
        // The inbound window follows from the outbound window plus the night range.
        assertEquals(LocalDate.of(2026, 11, 3), criteria.returnFrom());
        assertEquals(LocalDate.of(2026, 12, 3), criteria.returnTo());
    }

    @Test
    void noDaysTickedMeansEveryDay() {
        SearchCriteria criteria = form().toCriteria(directory);
        assertTrue(criteria.outboundDays().isAny());
        assertTrue(criteria.inboundDays().isAny());
    }

    @Test
    void defaultsToCivilisedDepartureTimes() {
        SearchCriteria criteria = form().toCriteria(directory);
        assertEquals(LocalTime.of(8, 0), criteria.outboundTime().from());
        assertEquals(LocalTime.of(22, 0), criteria.outboundTime().to());
        assertEquals(LocalTime.of(8, 0), criteria.inboundTime().from());
        assertEquals(LocalTime.of(22, 0), criteria.inboundTime().to());
    }

    @Test
    void theAnyPresetWidensTheWindowBackOut() {
        SearchForm form = form();
        form.setOutTimeFrom("00:00");
        form.setOutTimeTo("23:59");
        assertTrue(form.toCriteria(directory).outboundTime().isAny());
    }

    @Test
    void anEmptyDestinationIsAnAnywhereSearch() {
        SearchForm form = form();
        form.setTo("  ");
        assertTrue(form.toCriteria(directory).anywhere());
    }

    @Test
    void resolvesCitiesAndCountriesLikeTheCli() {
        SearchForm form = form();
        form.setFrom("dublin");
        form.setTo("spain, london");
        SearchCriteria criteria = form.toCriteria(directory);
        assertEquals(List.of("DUB"), criteria.origins());
        assertEquals(List.of("BCN", "STN"), criteria.destinations());
    }

    @Test
    void rejectsInputAUserCanFix() {
        SearchForm unknown = form();
        unknown.setFrom("Atlantis");
        assertThrows(IllegalArgumentException.class, () -> unknown.toCriteria(directory));

        SearchForm backwards = form();
        backwards.setOutTimeFrom("18:00");
        backwards.setOutTimeTo("09:00");
        assertThrows(IllegalArgumentException.class, () -> backwards.toCriteria(directory));

        SearchForm badDates = form();
        badDates.setDepartFrom("2026-11-30");
        badDates.setDepartTo("2026-11-01");
        assertThrows(IllegalArgumentException.class, () -> badDates.toCriteria(directory));

        SearchForm badBudget = form();
        badBudget.setBudget("cheap");
        assertThrows(IllegalArgumentException.class, () -> badBudget.toCriteria(directory));
    }
}
