package org.example.airplanner.duffel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.airplanner.model.Money;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;

/** Exactly one offer request per search. No retries, polling, or booking operations. */
public class DuffelClient implements AutoCloseable {
    private static final URI ENDPOINT = URI.create("https://api.duffel.com/air/offer_requests"
            + "?return_offers=true&supplier_timeout=10000&view=offers");
    private final DuffelProperties properties;
    private final SearchAllowance allowance;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Semaphore inFlight = new Semaphore(1);

    public DuffelClient(DuffelProperties properties, SearchAllowance allowance, HttpClient http) {
        this.properties = properties;
        this.allowance = allowance;
        this.http = http;
    }

    public Status status() {
        String reason = properties.unavailableReason();
        SearchAllowance.Usage usage = null;
        if (reason == null) {
            try { usage = allowance.read(); }
            catch (DuffelException e) { reason = e.getMessage(); }
        }
        return new Status(reason == null, properties.isLive(), reason, usage);
    }

    public Result search(OfferSearch search) {
        String reason = properties.unavailableReason();
        if (reason != null) throw new DuffelException(503, reason);
        if (!inFlight.tryAcquire()) throw new DuffelException(429, "A Duffel search is already running. Try again when it finishes.");
        try {
            HttpRequest request = HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(25))
                    .header("Authorization", "Bearer " + properties.getToken())
                    .header("Duffel-Version", "v2")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload(search).toString())).build();
            SearchAllowance.Usage usage = allowance.reserve();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String message = switch (response.statusCode()) {
                    case 401, 403 -> "Duffel rejected the server credentials or account permissions.";
                    case 429 -> "Duffel's request limit was reached. Try later.";
                    case 400, 422 -> "Duffel rejected these search details. Check airports, dates and passenger count.";
                    default -> "Duffel is temporarily unavailable. The request was not retried.";
                };
                throw new DuffelException(response.statusCode() == 429 ? 429 : 502, message);
            }
            return parse(response.body(), search, usage);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DuffelException(503, "Duffel search interrupted. The request still counts toward the local allowance.");
        } catch (IOException e) {
            throw new DuffelException(502, "Duffel did not return a usable response. The request was not retried and still counts toward the local allowance.");
        } finally { inFlight.release(); }
    }

    private ObjectNode payload(OfferSearch search) {
        ObjectNode root = mapper.createObjectNode();
        ObjectNode data = root.putObject("data");
        data.put("cabin_class", "economy");
        data.put("max_connections", search.maxConnections());
        data.put("include_split_ticket", false);
        var slices = data.putArray("slices");
        slices.addObject().put("origin", search.origin()).put("destination", search.destination())
                .put("departure_date", search.departureDate().toString());
        if (search.returnDate() != null) {
            slices.addObject().put("origin", search.destination()).put("destination", search.origin())
                    .put("departure_date", search.returnDate().toString());
        }
        var passengers = data.putArray("passengers");
        for (int i = 0; i < search.adults(); i++) passengers.addObject().put("type", "adult");
        return root;
    }

    private Result parse(String body, OfferSearch search, SearchAllowance.Usage usage) throws IOException {
        JsonNode root = mapper.readTree(body);
        JsonNode data = root == null ? null : root.get("data");
        if (data == null || !data.path("offers").isArray() || !data.path("live_mode").isBoolean()
                || data.path("live_mode").asBoolean() != properties.isLive()) {
            throw new IOException("Invalid offer response or mode");
        }
        List<FlightOffer> offers = new ArrayList<>();
        int skipped = 0;
        Instant fetchedAt = Instant.now();
        for (JsonNode offer : data.path("offers")) {
            try {
                FlightOffer parsed = offer(offer, search);
                if (!parsed.expiresAt().isAfter(fetchedAt) || !matches(parsed, search)) {
                    skipped++;
                    continue;
                }
                offers.add(parsed);
            } catch (IllegalArgumentException | java.time.DateTimeException e) { skipped++; }
        }
        offers.sort(Comparator.comparing((FlightOffer o) -> o.totalPrice().currency())
                .thenComparing(o -> o.totalPrice().amount()).thenComparing(FlightOffer::id));
        List<String> notes = new ArrayList<>();
        if (!properties.isLive()) notes.add("Test mode: these are sample offers, not live fares.");
        notes.add("Total prices cover all adults and both directions where requested. Currencies are grouped, not converted.");
        notes.add("Search only. Baggage, fare conditions and current availability must be checked before booking.");
        if (skipped > 0) notes.add(skipped + " expired, incomplete or non-matching offers were omitted.");
        if (offers.size() > 50) notes.add("Showing the first 50 offers, ordered by currency and total price.");
        return new Result(search, fetchedAt, properties.isLive(), offers.stream().limit(50).toList(), List.copyOf(notes), usage);
    }

    private FlightOffer offer(JsonNode node, OfferSearch search) {
        if (!node.path("live_mode").isBoolean() || node.path("live_mode").asBoolean() != properties.isLive()) {
            throw new IllegalArgumentException("Wrong mode");
        }
        if (!node.path("passengers").isArray() || node.path("passengers").size() != search.adults()) {
            throw new IllegalArgumentException("Wrong passenger count");
        }
        BigDecimal amount = new BigDecimal(required(node, "total_amount"));
        String currency = required(node, "total_currency");
        if (amount.signum() < 0 || !currency.matches("[A-Z]{3}")) throw new IllegalArgumentException("Invalid price");
        List<FlightOffer.Journey> journeys = new ArrayList<>();
        if (!node.path("slices").isArray()) throw new IllegalArgumentException("Missing slices");
        for (JsonNode slice : node.path("slices")) {
            List<FlightOffer.Segment> segments = new ArrayList<>();
            if (!slice.path("segments").isArray()) throw new IllegalArgumentException("Missing segments");
            for (JsonNode segment : slice.path("segments")) {
                segments.add(new FlightOffer.Segment(required(segment.path("origin"), "iata_code"),
                        required(segment.path("destination"), "iata_code"),
                        LocalDateTime.parse(required(segment, "departing_at")),
                        LocalDateTime.parse(required(segment, "arriving_at")),
                        required(segment.path("origin"), "time_zone"), required(segment.path("destination"), "time_zone"),
                        required(segment.path("operating_carrier"), "name"),
                        required(segment.path("marketing_carrier"), "name"),
                        segment.path("marketing_carrier").path("iata_code").asText("")
                                + segment.path("marketing_carrier_flight_number").asText(""),
                        segment.path("stops").size()));
            }
            if (segments.isEmpty()) throw new IllegalArgumentException("Empty journey");
            journeys.add(new FlightOffer.Journey(segments));
        }
        return new FlightOffer(required(node, "id"), "Duffel", new Money(amount, currency), search.adults(),
                Instant.parse(required(node, "expires_at")), properties.isLive(), journeys);
    }

    private boolean matches(FlightOffer offer, OfferSearch search) {
        if (offer.journeys().size() != (search.returnDate() == null ? 1 : 2)) return false;
        Instant previousArrival = null;
        for (int i = 0; i < offer.journeys().size(); i++) {
            var journey = offer.journeys().get(i);
            var first = journey.segments().getFirst();
            var last = journey.segments().getLast();
            if (journey.connections() > search.maxConnections()
                    || !first.origin().equals(i == 0 ? search.origin() : search.destination())
                    || !last.destination().equals(i == 0 ? search.destination() : search.origin())
                    || !first.departure().toLocalDate().equals(i == 0 ? search.departureDate() : search.returnDate())) return false;
            String previousAirport = null;
            for (var segment : journey.segments()) {
                Instant departure = segment.departure().atZone(java.time.ZoneId.of(segment.originTimeZone())).toInstant();
                Instant arrival = segment.arrival().atZone(java.time.ZoneId.of(segment.destinationTimeZone())).toInstant();
                if (!arrival.isAfter(departure) || (previousArrival != null && !departure.isAfter(previousArrival))
                        || (previousAirport != null && !previousAirport.equals(segment.origin()))) return false;
                previousArrival = arrival;
                previousAirport = segment.destination();
            }
        }
        return true;
    }

    private static String required(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException("Missing field");
        return value.asText();
    }

    @Override public void close() { http.close(); }
    public record Status(boolean ready, boolean liveMode, String reason, SearchAllowance.Usage usage) { }
    public record Result(OfferSearch search, Instant fetchedAt, boolean liveMode,
                         List<FlightOffer> offers, List<String> notes, SearchAllowance.Usage usage) { }
}
