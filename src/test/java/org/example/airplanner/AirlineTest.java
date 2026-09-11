package org.example.airplanner;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.WizzAirClient;
import org.example.airplanner.model.Airline;
import org.example.airplanner.model.Flight;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.Trip;
import org.example.airplanner.search.WizzAirRoutes;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Airline identification, links, and Wizz Air route coverage. */
class AirlineTest {

    @Test
    void namesCodesAndNicknamesAllResolve() {
        assertEquals(Airline.RYANAIR, Airline.parse("ryanair"));
        assertEquals(Airline.RYANAIR, Airline.parse("FR"));
        assertEquals(Airline.WIZZ_AIR, Airline.parse("wizzair"));
        assertEquals(Airline.WIZZ_AIR, Airline.parse("wizz air"));
        assertEquals(Airline.WIZZ_AIR, Airline.parse("wizz"));
        assertEquals(Airline.WIZZ_AIR, Airline.parse("W6"));
        assertThrows(IllegalArgumentException.class, () -> Airline.parse("smartwings"));
    }

    @Test
    void onlyRyanairClaimsFares() {
        assertTrue(Airline.RYANAIR.hasFares());
        assertFalse(Airline.WIZZ_AIR.hasFares());
        assertEquals(List.of(Airline.RYANAIR), Airline.withFares());
    }

    @Test
    void buildsASearchLinkForEachAirline() {
        LocalDate out = LocalDate.of(2026, 10, 16);
        LocalDate back = LocalDate.of(2026, 10, 18);

        String ryanair = Airline.RYANAIR.searchUrl("BTS", "BCN", out, back, 2);
        assertTrue(ryanair.contains("originIata=BTS"), ryanair);
        assertTrue(ryanair.contains("dateIn=2026-10-18"), ryanair);
        assertTrue(ryanair.contains("adults=2"), ryanair);

        String wizz = Airline.WIZZ_AIR.searchUrl("BTS", "BCN", out, back, 2);
        assertEquals("https://wizzair.com/en-gb/booking/select-flight/BTS/BCN/2026-10-16/2026-10-18/2/0/0/null",
                wizz);
        assertTrue(Airline.WIZZ_AIR.searchUrl("BTS", "BCN", out, null, 1).endsWith("/2026-10-16/null/1/0/0/null"));
    }

    @Test
    void everyTripCarriesANeutralComparisonLink() {
        Trip trip = new Trip(
                Flight.of("VIE", "BCN", LocalDateTime.of(2026, 10, 16, 9, 0),
                        LocalDateTime.of(2026, 10, 16, 11, 25), "FR123",
                        Money.of(40, "EUR"), ZoneId.of("Europe/Vienna"), ZoneId.of("Europe/Madrid")),
                Flight.of("BCN", "VIE", LocalDateTime.of(2026, 10, 18, 12, 0),
                        LocalDateTime.of(2026, 10, 18, 14, 25), "FR124",
                        Money.of(40, "EUR"), ZoneId.of("Europe/Madrid"), ZoneId.of("Europe/Vienna")),
                Money.of(80, "EUR"), 1);

        String url = trip.compareUrl();
        assertTrue(url.startsWith("https://www.google.com/travel/flights?q="), url);
        assertTrue(url.contains("VIE"), url);
        assertTrue(url.contains("BCN"), url);
        assertTrue(url.contains("2026-10-16"), url);
        assertTrue(url.contains("2026-10-18"), url);
    }

    @Test
    void wizzCoverageComesFromTheRouteMap() {
        WizzAirRoutes routes = new WizzAirRoutes(stubbedMap(Map.of(
                "BTS", Set.of("BCN", "ATH"),
                "VIE", Set.of())));

        assertTrue(routes.covers("BTS", "BCN"));
        assertTrue(routes.covers("bts", "bcn"), "codes are matched case-insensitively");
        assertFalse(routes.covers("VIE", "BCN"), "Wizz left Vienna, and the map says so");
        assertFalse(routes.covers("DUB", "STN"));
        assertEquals(List.of(Airline.WIZZ_AIR), routes.alsoFlying("BTS", "ATH"));
        assertEquals(List.of(), routes.alsoFlying("VIE", "BCN"));
    }

    @Test
    void wizzBeingUnreachableIsNotFatal() {
        WizzAirRoutes routes = new WizzAirRoutes(new WizzAirClient(offlineHttp()) {
            @Override
            public Map<String, Set<String>> routeMap() {
                throw new IllegalStateException("be.wizzair.com said no");
            }
        });
        assertFalse(routes.covers("BTS", "BCN"));
        assertEquals(List.of(), routes.alsoFlying("BTS", "BCN"));
    }

    private static WizzAirClient stubbedMap(Map<String, Set<String>> map) {
        return new WizzAirClient(offlineHttp()) {
            @Override
            public Map<String, Set<String>> routeMap() {
                return map;
            }
        };
    }

    private static HttpJsonClient offlineHttp() {
        return new HttpJsonClient(Path.of("build", "test-cache"), false, 1, 0, 1);
    }
}
