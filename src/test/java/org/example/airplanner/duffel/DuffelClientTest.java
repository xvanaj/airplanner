package org.example.airplanner.duffel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DuffelClientTest {
    @TempDir Path directory;
    private final HttpClient http = mock(HttpClient.class);
    private final DuffelProperties properties = new DuffelProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private DuffelClient client;
    private SearchAllowance budget;
    private final LocalDate date = LocalDate.now().plusDays(30);

    @BeforeEach void setup() {
        properties.setEnabled(true);
        properties.setToken("duffel_test_FAKE_NOT_A_CREDENTIAL");
        budget = new SearchAllowance(directory.resolve("usage.json"), 20, 500, Clock.systemUTC());
        client = new DuffelClient(properties, budget, http);
    }

    private OfferSearch query() { return new OfferSearch("PRG", "LHR", date, date.plusDays(3), 2, 0); }

    private ObjectNode response() throws Exception {
        String segment = """
                {"origin":{"iata_code":"PRG","time_zone":"Europe/Prague"},
                 "destination":{"iata_code":"LHR","time_zone":"Europe/London"},
                 "departing_at":"%sT10:00:00","arriving_at":"%sT11:00:00",
                 "operating_carrier":{"name":"Operating Airline"},
                 "marketing_carrier":{"name":"Marketing Airline","iata_code":"AB"},
                 "marketing_carrier_flight_number":"123","stops":[]}
                """.formatted(date, date);
        ObjectNode outbound = (ObjectNode) mapper.readTree(segment);
        ObjectNode inbound = outbound.deepCopy();
        inbound.set("origin", outbound.get("destination").deepCopy());
        inbound.set("destination", outbound.get("origin").deepCopy());
        inbound.put("departing_at", date.plusDays(3) + "T10:00:00");
        inbound.put("arriving_at", date.plusDays(3) + "T13:00:00");
        ObjectNode root = (ObjectNode) mapper.readTree("""
                {"data":{"id":"orq_example","live_mode":false,"offers":[{
                "id":"off_example","live_mode":false,"total_amount":"123.45","total_currency":"EUR",
                "expires_at":"2099-01-01T00:00:00Z","passengers":[{"id":"pas_1"},{"id":"pas_2"}],
                "slices":[]}]}}
                """);
        var slices = (com.fasterxml.jackson.databind.node.ArrayNode) root.at("/data/offers/0/slices");
        slices.addObject().putArray("segments").add(outbound);
        slices.addObject().putArray("segments").add(inbound);
        return root;
    }

    @SuppressWarnings("unchecked")
    private void reply(int status, String body) throws Exception {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    @Test void sendsOneRequestAndPreservesWholePartyPriceAndCarriers() throws Exception {
        reply(201, response().toString());
        var result = client.search(query());
        assertEquals("123.45 EUR", result.offers().getFirst().totalPrice().toString());
        assertEquals(2, result.offers().getFirst().adults());
        assertEquals("Operating Airline", result.offers().getFirst().journeys().getFirst().segments().getFirst().operatingCarrier());
        assertEquals(2, result.offers().getFirst().journeys().size());
        assertFalse(result.liveMode());
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, times(1)).send(captured.capture(), any());
        HttpRequest request = captured.getValue();
        assertEquals("POST", request.method());
        assertEquals("api.duffel.com", request.uri().getHost());
        assertEquals("v2", request.headers().firstValue("Duffel-Version").orElseThrow());
        var payload = mapper.readTree(body(request));
        assertEquals(2, payload.at("/data/passengers").size());
        assertEquals(2, payload.at("/data/slices").size());
        assertEquals(date.plusDays(3).toString(), payload.at("/data/slices/1/departure_date").asText());
        assertFalse(payload.at("/data/include_split_ticket").asBoolean());
        assertEquals(1, budget.read().monthlyUsed());
    }

    @Test void oneWaySendsOnlyOneSlice() throws Exception {
        reply(201, "{\"data\":{\"live_mode\":false,\"offers\":[]}}");
        client.search(new OfferSearch("PRG", "LHR", date, null, 1, 1));
        var captured = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(captured.capture(), any());
        assertEquals(1, mapper.readTree(body(captured.getValue())).at("/data/slices").size());
    }

    @Test void disabledOrLiveTokenInTestModeNeverCallsNetworkOrUsesAllowance() {
        properties.setEnabled(false);
        assertThrows(DuffelException.class, () -> client.search(query()));
        properties.setEnabled(true);
        properties.setToken("duffel_live_FAKE");
        assertThrows(DuffelException.class, () -> client.search(query()));
        assertFalse(client.status().ready());
        verifyNoInteractions(http);
        assertEquals(0, budget.read().dailyUsed());
    }

    @Test void upstreamFailureIsNotRetriedAndDoesNotLeakResponseOrRefundBudget() throws Exception {
        reply(429, "private token, passenger data, upstream details");
        var error = assertThrows(DuffelException.class, () -> client.search(query()));
        assertEquals(429, error.status());
        assertFalse(error.getMessage().contains("private"));
        verify(http, times(1)).send(any(), any());
        assertEquals(1, budget.read().dailyUsed());
    }

    @Test void exhaustedAllowanceNeverSendsRequest() {
        var limited = new SearchAllowance(directory.resolve("limited.json"), 1, 1, Clock.systemUTC());
        limited.reserve();
        var guarded = new DuffelClient(properties, limited, http);
        assertEquals(429, assertThrows(DuffelException.class, () -> guarded.search(query())).status());
        verifyNoInteractions(http);
    }

    @Test void expiredMalformedAndWrongPassengerOffersAreOmitted() throws Exception {
        var response = response();
        var offers = (com.fasterxml.jackson.databind.node.ArrayNode) response.at("/data/offers");
        ObjectNode original = (ObjectNode) offers.get(0);
        offers.add(original.deepCopy().put("expires_at", "2000-01-01T00:00:00Z"));
        offers.add(original.deepCopy().put("expires_at", "invalid"));
        offers.add(original.deepCopy().set("passengers", mapper.createArrayNode()));
        reply(201, response.toString());
        var result = client.search(query());
        assertEquals(1, result.offers().size());
        assertTrue(result.notes().stream().anyMatch(s -> s.startsWith("3 expired")));
    }

    @Test void rejectsUnexpectedLiveResponse() throws Exception {
        var response = response();
        ((ObjectNode) response.get("data")).put("live_mode", true);
        reply(201, response.toString());
        assertEquals(502, assertThrows(DuffelException.class, () -> client.search(query())).status());
    }

    @Test void preservesConnectionsAndLocallyEnforcesTheConnectionLimit() throws Exception {
        var response = response();
        var segments = (com.fasterxml.jackson.databind.node.ArrayNode) response.at("/data/offers/0/slices/0/segments");
        ObjectNode first = (ObjectNode) segments.get(0);
        ObjectNode second = first.deepCopy();
        first.putObject("destination").put("iata_code", "FRA").put("time_zone", "Europe/Berlin");
        second.set("origin", first.get("destination").deepCopy());
        second.put("departing_at", date + "T12:00:00");
        second.put("arriving_at", date + "T13:00:00");
        segments.add(second);
        reply(201, response.toString());
        var connecting = client.search(new OfferSearch("PRG", "LHR", date, date.plusDays(3), 2, 1));
        assertEquals(1, connecting.offers().size());
        assertEquals(1, connecting.offers().getFirst().journeys().getFirst().connections());
        assertEquals("123.45 EUR", connecting.offers().getFirst().totalPrice().toString());
        assertTrue(client.search(query()).offers().isEmpty());
    }

    @Test void currenciesAreGroupedRatherThanComparedAsEquivalentAmounts() throws Exception {
        var response = response();
        var offers = (com.fasterxml.jackson.databind.node.ArrayNode) response.at("/data/offers");
        ObjectNode original = (ObjectNode) offers.get(0);
        offers.add(original.deepCopy().put("id", "off_usd").put("total_currency", "USD").put("total_amount", "1.00"));
        offers.add(original.deepCopy().put("id", "off_eur").put("total_amount", "100.00"));
        reply(201, response.toString());
        assertEquals(java.util.List.of("off_eur", "off_example", "off_usd"),
                client.search(query()).offers().stream().map(FlightOffer::id).toList());
    }

    @Test void inputValidationRejectsFanoutAndBadDates() {
        assertThrows(IllegalArgumentException.class, () -> new OfferSearch("PRG,VIE", "LHR", date, null, 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OfferSearch("PRG", "LHR", date, date.minusDays(1), 1, 0));
        assertThrows(IllegalArgumentException.class, () -> new OfferSearch("PRG", "LHR", date, null, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new OfferSearch("PRG", "LHR", date, null, 1, 3));
        assertEquals("PRG", new OfferSearch(" prg ", "lhr", date, null, 1, 0).origin());
    }

    private static String body(HttpRequest request) throws Exception {
        var result = new CompletableFuture<String>();
        var bytes = new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer buffer) { byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk); }
            public void onError(Throwable error) { result.completeExceptionally(error); }
            public void onComplete() { result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8)); }
        });
        return result.get();
    }
}
