package org.example.airplanner.web;

import org.example.airplanner.model.Airport;
import org.example.airplanner.model.Flight;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.Trip;
import org.example.airplanner.search.AirportDirectory;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class AccommodationLinksTest {
    private static final AirportDirectory DIRECTORY = directory("London", "United Kingdom");

    @Test
    void usesDestinationCityAndLocalArrivalForOvernightFlights() {
        AccommodationLinks result = AccommodationLinks.forTrip(trip("2026-11-02T01:00", "2026-11-04T12:00"),
                DIRECTORY, "");
        assertEquals("London, United Kingdom", result.destination());
        assertEquals("2026-11-02 to 2026-11-04", result.dates());
        assertEquals(List.of("Airbnb", "Booking.com", "Expedia", "Hotels.com", "Vrbo", "Agoda"),
                result.links().stream().map(AccommodationLinks.Link::name).toList());
        for (var link : result.links()) {
            Map<String, String> params = query(link);
            String[] keys = searchKeys(link.name());
            assertEquals("2026-11-02", params.get(keys[1]), link.name());
            assertEquals("2026-11-04", params.get(keys[2]), link.name());
            assertEquals("London, United Kingdom", params.get(keys[0]), link.name());
            assertEquals("2", params.get(link.name().equals("Booking.com") ? "group_adults" : "adults"));
            assertEquals("https", URI.create(link.url()).getScheme());
        }
    }

    @Test
    void requiresConfiguredAffiliateIdAndEncodesItWithoutAddingParameters() {
        Trip trip = trip("2026-11-01T12:00", "2026-11-04T12:00");
        for (String aid : Arrays.asList(null, "", "  ")) {
            assertEquals(6, AccommodationLinks.forTrip(trip, DIRECTORY, aid).links().size());
        }
        var link = AccommodationLinks.forTrip(trip, DIRECTORY, " partner &x=1 ").links().getLast();
        assertEquals("www.stay22.com", URI.create(link.url()).getHost());
        assertEquals("/allez/roam", URI.create(link.url()).getPath());
        assertEquals("partner &x=1", query(link).get("aid"));
        assertFalse(query(link).containsKey("x"));
        assertEquals("2026-11-01", query(link).get("checkin"));
    }

    @Test
    void doesNotInventDatesForOneWaySameDayOrUnknownArrival() {
        List<Trip> trips = List.of(trip("2026-11-01T12:00", null),
                trip("2026-11-01T12:00", "2026-11-01T18:00"),
                trip(null, "2026-11-04T12:00"));
        for (Trip trip : trips) {
            var result = AccommodationLinks.forTrip(trip, DIRECTORY, "partner");
            assertTrue(result.dates().contains("Choose stay dates"));
            result.links().forEach(link -> {
                String[] keys = searchKeys(link.name());
                assertFalse(query(link).containsKey(keys[1]), link.name());
                assertFalse(query(link).containsKey(keys[2]), link.name());
            });
        }
    }

    @Test
    void preservesUnicodeAndQueryCharactersInLocations() {
        var result = AccommodationLinks.forTrip(trip("2026-11-01T12:00", null),
                directory("São Tomé & Príncipe / +?", "ST"), "");
        for (var link : result.links()) {
            assertEquals("São Tomé & Príncipe / +?, ST", query(link).get(searchKeys(link.name())[0]), link.name());
        }
        assertEquals(4, query(result.links().getFirst()).size());
    }

    @Test
    void fallsBackWhenCityOrAirportIsMissing() {
        Trip trip = trip("2026-11-01T12:00", null);
        assertEquals("London Stansted, United Kingdom", AccommodationLinks.forTrip(trip,
                directory(null, "United Kingdom"), "").destination());
        assertEquals("STN", AccommodationLinks.forTrip(trip, new AirportDirectory(List.of()), "").destination());
    }

    @Test
    void defaultsToTwoAdultsAndThreeChildrenRegardlessOfFlightPassengers() {
        var result = AccommodationLinks.forTrip(trip("2026-11-01T12:00", null), DIRECTORY, "partner");
        assertEquals("2 adults · 3 children (ages 2, 5, 7)", result.guests());
        for (var link : result.links()) {
            var params = query(link);
            assertEquals("2", params.get(link.name().equals("Booking.com") ? "group_adults" : "adults"));
            switch (link.name()) {
                case "Booking.com" -> {
                    assertEquals("3", params.get("group_children"));
                    assertEquals("2,5,7", params.get("age"));
                }
                case "Expedia", "Hotels.com" -> assertEquals("1_2,1_5,1_7", params.get("children"));
                case "Agoda", "Stay22" -> {
                    assertEquals("3", params.get("children"));
                    assertEquals("2,5,7", params.get(link.name().equals("Agoda") ? "childAges" : "childrenAges"));
                }
                case "Airbnb", "Vrbo" -> {
                    assertEquals("3", params.get("children"));
                    assertEquals("0", params.get("infants"));
                }
                default -> fail("Unknown provider");
            }
        }
    }

    @Test
    void allowsAnAdultOnlyPartyWithoutLeakingDefaultChildAges() {
        var result = AccommodationLinks.forTrip(trip("2026-11-01T12:00", null), DIRECTORY, "partner", 1, List.of());
        assertEquals("1 adults · 0 children", result.guests());
        for (var link : result.links()) {
            var params = query(link);
            assertEquals("1", params.get(link.name().equals("Booking.com") ? "group_adults" : "adults"));
            assertFalse(params.containsKey("age"));
            assertFalse(params.containsKey("childAges"));
            assertFalse(params.containsKey("childrenAges"));
            assertTrue(params.getOrDefault("children", "0").equals("0"));
        }
    }

    @Test
    void rejectsInvalidAccommodationGuests() {
        Trip trip = trip("2026-11-01T12:00", null);
        assertThrows(IllegalArgumentException.class, () -> AccommodationLinks.forTrip(trip, DIRECTORY, "", 0, List.of(2)));
        assertThrows(IllegalArgumentException.class, () -> AccommodationLinks.forTrip(trip, DIRECTORY, "", 2, List.of(-1)));
        assertThrows(IllegalArgumentException.class, () -> AccommodationLinks.forTrip(trip, DIRECTORY, "", 2, List.of(18)));
    }

    private static String[] searchKeys(String provider) {
        return switch (provider) {
            case "Airbnb" -> new String[]{"query", "checkin", "checkout"};
            case "Booking.com" -> new String[]{"ss", "checkin", "checkout"};
            case "Expedia", "Hotels.com", "Vrbo" -> new String[]{"destination", "startDate", "endDate"};
            case "Agoda" -> new String[]{"textToSearch", "checkIn", "checkOut"};
            case "Stay22" -> new String[]{"address", "checkin", "checkout"};
            default -> throw new AssertionError("Unexpected provider: " + provider);
        };
    }

    private static Map<String, String> query(AccommodationLinks.Link link) {
        return Arrays.stream(URI.create(link.url()).getRawQuery().split("&"))
                .map(pair -> pair.split("=", 2))
                .collect(Collectors.toMap(pair -> pair[0], pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8),
                        (first, next) -> first + "," + next));
    }

    private static AirportDirectory directory(String city, String country) {
        return new AirportDirectory(List.of(new Airport("STN", "London Stansted", city, "England",
                country, "gb", 51.9, 0.2, "Europe/London", true)));
    }

    private static Trip trip(String arrival, String returnDeparture) {
        Flight out = Flight.of("VIE", "STN", LocalDateTime.parse("2026-11-01T10:00"),
                arrival == null ? null : LocalDateTime.parse(arrival), "FR1234", Money.of(42, "EUR"),
                ZoneOffset.UTC, ZoneOffset.UTC);
        LocalDateTime backDate = returnDeparture == null ? null : LocalDateTime.parse(returnDeparture);
        Flight back = backDate == null ? null : Flight.of("STN", "VIE", backDate, backDate.plusHours(2),
                "FR1235", Money.of(42, "EUR"), ZoneOffset.UTC, ZoneOffset.UTC);
        return new Trip(out, back, Money.of(84, "EUR"), 1);
    }
}
