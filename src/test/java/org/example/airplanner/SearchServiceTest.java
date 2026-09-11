package org.example.airplanner;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.Airport;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.model.Trip;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.FlightSearchService;
import org.example.airplanner.search.SearchResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the day-of-week / time-of-day filtering and trip assembly against a stubbed API. */
class SearchServiceTest {

    private static final YearMonth MONTH = YearMonth.from(LocalDate.now().plusMonths(1));

    private final AirportDirectory directory = new AirportDirectory(List.of(
            new Airport("DUB", "Dublin", "Dublin", "Leinster", "Ireland", "ie", 53.4, -6.2, "Europe/Dublin", true),
            new Airport("STN", "London Stansted", "London", "England", "United Kingdom", "gb", 51.9, 0.2, "Europe/London", true),
            new Airport("BCN", "Barcelona", "Barcelona", "Costa Brava", "Spain", "es", 41.3, 2.1, "Europe/Madrid", true)));

    /**
     * Outbound: an evening flight (20:00) on Fridays, an early one (07:00) on every other day.
     * Inbound: a morning flight (09:00) every day.
     */
    private final RyanairClient stub = new RyanairClient(new HttpJsonClient(Path.of("build", "test-cache"),
            false, 1, 0, 1)) {

        @Override
        public Set<String> destinationsFrom(String origin) {
            return Set.of("STN", "BCN");
        }

        @Override
        public List<DayFare> cheapestPerDay(String origin, String destination, YearMonth month, String currency) {
            List<DayFare> fares = new ArrayList<>();
            for (int day = 1; day <= month.lengthOfMonth(); day++) {
                LocalDate date = month.atDay(day);
                boolean outbound = origin.equals("DUB");
                LocalTime departure = outbound
                        ? (date.getDayOfWeek() == DayOfWeek.FRIDAY ? LocalTime.of(20, 0) : LocalTime.of(7, 0))
                        : LocalTime.of(9, 0);
                double price = outbound ? 20 : 15;
                fares.add(new DayFare(date, LocalDateTime.of(date, departure),
                        LocalDateTime.of(date, departure.plusHours(2)), price, currency, true));
            }
            return fares;
        }

        @Override
        public Optional<FareLeg> cheapestOneWay(String origin, String destination, LocalDate from, LocalDate to,
                                                TimeWindow window, DayFilter days, String currency, int adults) {
            return Optional.empty();
        }
    };

    private SearchCriteria.Builder criteria() {
        return SearchCriteria.builder()
                .origins(List.of("DUB"))
                .destinations(List.of("STN"))
                .departFrom(MONTH.atDay(1))
                .departTo(MONTH.atEndOfMonth())
                .limit(50)
                .perRoute(50);
    }

    private SearchResult search(SearchCriteria criteria) {
        HttpJsonClient http = new HttpJsonClient(Path.of("build", "test-cache"), false, 1, 0, 1);
        return new FlightSearchService(stub, directory, http, 100).search(criteria);
    }

    @Test
    void keepsOnlyDaysAndTimesThatWereAskedFor() {
        SearchResult result = search(criteria()
                .outboundDays(DayFilter.parse("fri"))
                .outboundTime(TimeWindow.parse("evening"))
                .inboundDays(DayFilter.parse("sun"))
                .inboundTime(TimeWindow.parse("morning"))
                .minNights(2).maxNights(2)
                .build());

        assertFalse(result.trips().isEmpty());
        for (Trip trip : result.trips()) {
            assertEquals(DayOfWeek.FRIDAY, trip.outbound().date().getDayOfWeek());
            assertEquals(DayOfWeek.SUNDAY, trip.inbound().date().getDayOfWeek());
            assertEquals(LocalTime.of(20, 0), trip.outbound().departureTime());
            assertEquals(LocalTime.of(9, 0), trip.inbound().departureTime());
            assertEquals(2, trip.nights());
            assertEquals("35.00 EUR", trip.pricePerAdult().toString());
        }
    }

    @Test
    void dropsDaysWhoseOnlyFlightIsOutsideTheWindow() {
        // Only Fridays have an evening departure, so a Monday-only search finds nothing.
        SearchResult result = search(criteria()
                .outboundDays(DayFilter.parse("mon"))
                .outboundTime(TimeWindow.parse("evening"))
                .build());
        assertTrue(result.trips().isEmpty());
    }

    @Test
    void appliesBudgetAndNightRange() {
        SearchResult cheap = search(criteria()
                .maxPrice(org.example.airplanner.model.Money.of(34, "EUR"))
                .minNights(1).maxNights(3)
                .build());
        assertTrue(cheap.trips().isEmpty(), "every trip here costs 35.00");

        SearchResult wide = search(criteria().minNights(3).maxNights(3).build());
        assertFalse(wide.trips().isEmpty());
        wide.trips().forEach(t -> assertEquals(3, t.nights()));
    }

    @Test
    void oneWaySearchSkipsTheReturnLeg() {
        SearchResult result = search(criteria().oneWay(true).build());
        assertFalse(result.trips().isEmpty());
        result.trips().forEach(t -> {
            assertFalse(t.isReturn());
            assertEquals("20.00 EUR", t.pricePerAdult().toString());
        });
    }

    @Test
    void skipsRoutesRyanairDoesNotFly() {
        SearchResult result = search(criteria().destinations(List.of("STN", "XXX")).build());
        assertEquals(1, result.routesScanned());
        assertEquals(1, result.routesSkipped());
    }

    @Test
    void buildsABookingLinkForTheExactDates() {
        SearchResult result = search(criteria()
                .outboundDays(DayFilter.parse("fri"))
                .minNights(2).maxNights(2)
                .build());
        Trip trip = result.trips().get(0);
        String url = trip.bookingUrl();
        assertTrue(url.startsWith("https://www.ryanair.com/gb/en/trip/flights/select?"), url);
        assertTrue(url.contains("originIata=DUB"), url);
        assertTrue(url.contains("destinationIata=STN"), url);
        assertTrue(url.contains("dateOut=" + trip.outbound().date()), url);
        assertTrue(url.contains("dateIn=" + trip.inbound().date()), url);
        assertTrue(url.contains("isReturn=true"), url);
    }

    @Test
    void resolvesAirportsByCodeCityAndCountry() {
        assertEquals(List.of("DUB"), directory.resolveCodes(List.of("dub")));
        assertEquals(List.of("STN"), directory.resolveCodes(List.of("london")));
        assertEquals(List.of("BCN"), directory.resolveCodes(List.of("spain")));
        assertEquals(List.of("DUB", "STN"), directory.resolveCodes(List.of("DUB,STN")));
    }
}
