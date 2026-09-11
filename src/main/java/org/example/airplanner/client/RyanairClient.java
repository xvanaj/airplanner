package org.example.airplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.airplanner.model.Airport;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.TimeWindow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Thin, read-only wrapper over the public endpoints ryanair.com itself calls from
 * the browser. Nothing here books or reserves anything.
 */
public class RyanairClient {

    private static final String FARE_FINDER = "https://services-api.ryanair.com/farfnd/v4";
    private static final String FARE_FINDER_V3 = "https://services-api.ryanair.com/farfnd/3";
    private static final String VIEWS = "https://www.ryanair.com/api/views/locate";
    private static final String TIMETABLE = "https://www.ryanair.com/api/timtbl/3";

    /** The fare finder rejects any limit above this. */
    private static final int PAGE_SIZE = 16;
    private static final int MAX_PAGES = 25;

    private static final Duration STATIC_TTL = Duration.ofHours(24);
    private static final Duration FARE_TTL = Duration.ofMinutes(30);

    private final HttpJsonClient http;
    private final String market;
    private final Duration fareTtl;

    public RyanairClient(HttpJsonClient http, String market, Duration fareTtl) {
        this.http = http;
        this.market = market == null || market.isBlank() ? "en-gb" : market;
        this.fareTtl = fareTtl == null ? FARE_TTL : fareTtl;
    }

    public RyanairClient(HttpJsonClient http) {
        this(http, "en-gb", FARE_TTL);
    }

    // ---------------------------------------------------------------- static data

    /** Every airport Ryanair currently flies from. */
    public List<Airport> activeAirports() {
        JsonNode root = http.get(VIEWS + "/5/airports/en/active", STATIC_TTL);
        List<Airport> airports = new ArrayList<>();
        for (JsonNode n : root) {
            Airport airport = toAirport(n);
            if (airport != null) {
                airports.add(airport);
            }
        }
        return airports;
    }

    /** IATA codes reachable on a direct Ryanair flight from {@code origin}. */
    public Set<String> destinationsFrom(String origin) {
        JsonNode root = http.get(VIEWS + "/searchWidget/routes/en/airport/" + enc(origin), STATIC_TTL);
        Set<String> codes = new LinkedHashSet<>();
        for (JsonNode n : root) {
            String code = n.path("arrivalAirport").path("code").asText(null);
            if (code != null) {
                codes.add(code);
            }
        }
        return codes;
    }

    /** Published timetable (all flights, no prices) for a route in one month. */
    public List<TimetableFlight> timetable(String origin, String destination, YearMonth month) {
        String url = TIMETABLE + "/schedules/" + enc(origin) + "/" + enc(destination)
                + "/years/" + month.getYear() + "/months/" + month.getMonthValue();
        JsonNode root = http.get(url, STATIC_TTL);
        List<TimetableFlight> flights = new ArrayList<>();
        for (JsonNode day : root.path("days")) {
            int dayOfMonth = day.path("day").asInt(0);
            if (dayOfMonth < 1) {
                continue;
            }
            LocalDate date = month.atDay(dayOfMonth);
            for (JsonNode f : day.path("flights")) {
                LocalTime departure = time(f.path("departureTime").asText(null));
                LocalTime arrival = time(f.path("arrivalTime").asText(null));
                if (departure == null) {
                    continue;
                }
                String number = f.path("carrierCode").asText("FR") + f.path("number").asText("");
                flights.add(new TimetableFlight(date, departure, arrival, number.trim()));
            }
        }
        return flights;
    }

    // ---------------------------------------------------------------- fares

    /**
     * Cheapest fare on every day of one month for one route. One request covers a whole
     * month, which makes it the cheap way to scan a long date range.
     */
    public List<DayFare> cheapestPerDay(String origin, String destination, YearMonth month, String currency) {
        String url = FARE_FINDER_V3 + "/oneWayFares/" + enc(origin) + "/" + enc(destination) + "/cheapestPerDay"
                + "?outboundMonthOfDate=" + month.atDay(1)
                + "&currency=" + enc(currency);
        JsonNode root = http.get(url, fareTtl);
        List<DayFare> fares = new ArrayList<>();
        for (JsonNode n : root.path("outbound").path("fares")) {
            LocalDate day = date(n.path("day").asText(null));
            if (day == null) {
                continue;
            }
            boolean unavailable = n.path("unavailable").asBoolean(false) || n.path("soldOut").asBoolean(false);
            LocalDateTime departure = dateTime(n.path("departureDate").asText(null));
            LocalDateTime arrival = dateTime(n.path("arrivalDate").asText(null));
            JsonNode price = n.path("price");
            Double value = price.isMissingNode() || price.isNull() ? null : price.path("value").asDouble();
            String cur = price.path("currencyCode").asText(currency);
            fares.add(new DayFare(day, departure, arrival, value, cur, !unavailable && value != null));
        }
        return fares;
    }

    /**
     * Cheapest single fare on a route inside a date range, honouring a departure time
     * window and a weekday filter server-side. Returns at most one fare - the API's
     * job here is "what is the best price under these constraints".
     */
    public Optional<FareLeg> cheapestOneWay(String origin, String destination, LocalDate from, LocalDate to,
                                            TimeWindow window, DayFilter days, String currency, int adults) {
        StringBuilder url = new StringBuilder(FARE_FINDER + "/oneWayFares?");
        url.append("departureAirportIataCode=").append(enc(origin));
        if (destination != null && !destination.isBlank()) {
            url.append("&arrivalAirportIataCode=").append(enc(destination));
        }
        appendOutboundFilters(url, from, to, window, days);
        url.append("&currency=").append(enc(currency));
        url.append("&adultPaxCount=").append(Math.max(1, adults));
        url.append("&market=").append(enc(market));
        url.append("&limit=1&offset=0");
        JsonNode root = http.get(url.toString(), fareTtl);
        for (JsonNode fare : root.path("fares")) {
            FareLeg leg = toLeg(fare.path("outbound"), currency);
            if (leg != null) {
                return Optional.of(leg);
            }
        }
        return Optional.empty();
    }

    /**
     * "Anywhere" search: the cheapest fare to each destination reachable from
     * {@code origin} inside the constraints. One fare per destination.
     */
    public List<FareLeg> cheapestOneWayAnywhere(String origin, LocalDate from, LocalDate to, TimeWindow window,
                                                DayFilter days, String currency, int adults, int limit) {
        StringBuilder url = new StringBuilder(FARE_FINDER + "/oneWayFares?");
        url.append("departureAirportIataCode=").append(enc(origin));
        appendOutboundFilters(url, from, to, window, days);
        url.append("&currency=").append(enc(currency));
        url.append("&adultPaxCount=").append(Math.max(1, adults));
        url.append("&market=").append(enc(market));
        List<FareLeg> legs = new ArrayList<>();
        for (JsonNode fare : pagedFares(url.toString(), limit)) {
            FareLeg leg = toLeg(fare.path("outbound"), currency);
            if (leg != null) {
                legs.add(leg);
            }
        }
        return legs;
    }

    /**
     * "Anywhere" return search: the cheapest round trip to each destination reachable
     * from {@code origin}, with both directions constrained by day and time.
     */
    public List<RoundTripFare> cheapestRoundTripAnywhere(String origin, LocalDate outFrom, LocalDate outTo,
                                                         LocalDate inFrom, LocalDate inTo,
                                                         TimeWindow outWindow, TimeWindow inWindow,
                                                         DayFilter outDays, DayFilter inDays,
                                                         int minNights, int maxNights,
                                                         String currency, int adults, int limit) {
        StringBuilder url = new StringBuilder(FARE_FINDER + "/roundTripFares?");
        url.append("departureAirportIataCode=").append(enc(origin));
        appendOutboundFilters(url, outFrom, outTo, outWindow, outDays);
        url.append("&inboundDepartureDateFrom=").append(inFrom).append("&inboundDepartureDateTo=").append(inTo);
        if (!inWindow.isAny()) {
            url.append("&inboundDepartureTimeFrom=").append(enc(inWindow.apiFrom()));
            url.append("&inboundDepartureTimeTo=").append(enc(inWindow.apiTo()));
        }
        if (!inDays.isAny()) {
            url.append("&inboundDepartureDaysOfWeek=").append(enc(inDays.apiValue()));
        }
        url.append("&durationFrom=").append(Math.max(0, minNights)).append("&durationTo=").append(maxNights);
        url.append("&currency=").append(enc(currency));
        url.append("&adultPaxCount=").append(Math.max(1, adults));
        url.append("&market=").append(enc(market));
        List<RoundTripFare> trips = new ArrayList<>();
        for (JsonNode fare : pagedFares(url.toString(), limit)) {
            FareLeg out = toLeg(fare.path("outbound"), currency);
            FareLeg back = toLeg(fare.path("inbound"), currency);
            if (out != null) {
                trips.add(new RoundTripFare(out, back));
            }
        }
        return trips;
    }

    /**
     * The fare finder accepts at most {@value #PAGE_SIZE} fares per request, so an
     * "anywhere" search walks through the pages until it runs out or hits {@code limit}.
     */
    private List<JsonNode> pagedFares(String baseUrl, int limit) {
        List<JsonNode> fares = new ArrayList<>();
        int wanted = Math.max(1, limit);
        for (int offset = 0; fares.size() < wanted && offset < MAX_PAGES * PAGE_SIZE; offset += PAGE_SIZE) {
            JsonNode root = http.get(baseUrl + "&limit=" + PAGE_SIZE + "&offset=" + offset, fareTtl);
            JsonNode page = root.path("fares");
            int received = 0;
            for (JsonNode fare : page) {
                fares.add(fare);
                received++;
            }
            if (received < PAGE_SIZE) {
                break;
            }
        }
        return fares.size() > wanted ? fares.subList(0, wanted) : fares;
    }

    private void appendOutboundFilters(StringBuilder url, LocalDate from, LocalDate to,
                                       TimeWindow window, DayFilter days) {
        url.append("&outboundDepartureDateFrom=").append(from).append("&outboundDepartureDateTo=").append(to);
        if (window != null && !window.isAny()) {
            url.append("&outboundDepartureTimeFrom=").append(enc(window.apiFrom()));
            url.append("&outboundDepartureTimeTo=").append(enc(window.apiTo()));
        }
        if (days != null && !days.isAny()) {
            url.append("&outboundDepartureDaysOfWeek=").append(enc(days.apiValue()));
        }
    }

    // ---------------------------------------------------------------- parsing helpers

    private static Airport toAirport(JsonNode n) {
        String code = n.path("code").asText(null);
        if (code == null) {
            return null;
        }
        return new Airport(
                code,
                n.path("name").asText(code),
                n.path("city").path("name").asText(""),
                n.path("region").path("name").asText(""),
                n.path("country").path("name").asText(""),
                n.path("country").path("code").asText(""),
                n.path("coordinates").path("latitude").asDouble(0),
                n.path("coordinates").path("longitude").asDouble(0),
                n.path("timeZone").asText(null),
                n.path("base").asBoolean(false));
    }

    private static FareLeg toLeg(JsonNode node, String fallbackCurrency) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        LocalDateTime departure = dateTime(node.path("departureDate").asText(null));
        if (departure == null) {
            return null;
        }
        return new FareLeg(
                node.path("departureAirport").path("iataCode").asText(""),
                node.path("arrivalAirport").path("iataCode").asText(""),
                departure,
                dateTime(node.path("arrivalDate").asText(null)),
                node.path("flightNumber").asText(""),
                node.path("price").path("value").asDouble(0),
                node.path("price").path("currencyCode").asText(fallbackCurrency));
    }

    private static LocalDateTime dateTime(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return LocalDateTime.parse(raw.length() > 19 ? raw.substring(0, 19) : raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalDate date(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static LocalTime time(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return null;
        }
        try {
            return LocalTime.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- payload records

    /** One priced sector as the fare finder returns it. */
    public record FareLeg(String origin, String destination, LocalDateTime departure, LocalDateTime arrival,
                          String flightNumber, double price, String currency) {
    }

    /** An outbound leg with its matching inbound leg. */
    public record RoundTripFare(FareLeg outbound, FareLeg inbound) {
    }

    /** The cheapest fare available on one calendar day. */
    public record DayFare(LocalDate day, LocalDateTime departure, LocalDateTime arrival,
                          Double price, String currency, boolean available) {
    }

    /** A scheduled flight with no price attached. */
    public record TimetableFlight(LocalDate date, LocalTime departure, LocalTime arrival, String flightNumber) {
    }
}
