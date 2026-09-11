package org.example.airplanner.web;

import org.example.airplanner.duffel.*;
import org.example.airplanner.model.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DuffelController.class)
class DuffelControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean DuffelClient client;
    private final LocalDate date = LocalDate.now().plusDays(30);

    @BeforeEach void setup() {
        when(client.status()).thenReturn(new DuffelClient.Status(true, false, null,
                new SearchAllowance.Usage("2026-09-09", "2026-09", 0, 0, 20, 500)));
    }

    @Test void pageAndStatusNeverSearchOrExposeCredentials() throws Exception {
        mvc.perform(get("/duffel")).andExpect(status().isOk())
                .andExpect(content().string(containsString("sample fares only")))
                .andExpect(content().string(not(containsString("duffel_test_"))));
        mvc.perform(get("/api/duffel/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.csrfToken").isString())
                .andExpect(jsonPath("$.provider.ready").value(true))
                .andExpect(content().string(not(containsString("duffel_test_"))));
        verify(client, never()).search(any());
    }

    @Test void disabledPageExplainsSetupAndDisablesSubmit() throws Exception {
        when(client.status()).thenReturn(new DuffelClient.Status(false, false, "Duffel is disabled.", null));
        mvc.perform(get("/duffel")).andExpect(status().isOk())
                .andExpect(content().string(containsString("Duffel is disabled.")))
                .andExpect(content().string(containsString("disabled=\"disabled\"")));
    }

    @Test void jsonSearchRequiresSessionToken() throws Exception {
        mvc.perform(post("/api/duffel/search").contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(client, never()).search(any());
    }

    @Test void invalidFormNeverSpendsARequest() throws Exception {
        MockHttpSession session = session();
        var result = mvc.perform(post("/duffel").session(session)
                        .param("csrf", (String) session.getAttribute("duffelCsrf"))
                        .param("origin", "PRG,VIE").param("destination", "LHR")
                        .param("departureDate", date.toString()))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/duffel")).andReturn();
        mvc.perform(get("/duffel").session(session).flashAttrs(result.getFlashMap()))
                .andExpect(content().string(containsString("three-letter IATA")));
        verify(client, never()).search(any());
    }

    @Test void successfulPostRedirectsAndRendersWholePriceOperatingCarrierAndReturn() throws Exception {
        var search = new OfferSearch("PRG", "LHR", date, date.plusDays(2), 2, 1);
        var outbound = new FlightOffer.Segment("PRG", "LHR", date.atTime(10, 0), date.atTime(11, 0),
                "Europe/Prague", "Europe/London", "Operating <Airline>", "Marketing Airline", "AB123", 0);
        var inbound = new FlightOffer.Segment("LHR", "PRG", date.plusDays(2).atTime(10, 0), date.plusDays(2).atTime(13, 0),
                "Europe/London", "Europe/Prague", "Return Airline", "Return Airline", "AB124", 0);
        var offer = new FlightOffer("off_example", "Duffel", Money.of(123.45, "EUR"), 2,
                Instant.now().plusSeconds(600), false, List.of(new FlightOffer.Journey(List.of(outbound)),
                new FlightOffer.Journey(List.of(inbound))));
        when(client.search(any())).thenReturn(new DuffelClient.Result(search, Instant.now(), false,
                List.of(offer), List.of("Sample offers"), null));
        MockHttpSession session = session();
        var submitted = mvc.perform(post("/duffel").session(session)
                        .param("csrf", (String) session.getAttribute("duffelCsrf"))
                        .param("origin", "PRG").param("destination", "LHR").param("adults", "2")
                        .param("departureDate", date.toString()).param("returnDate", date.plusDays(2).toString()))
                .andExpect(status().is3xxRedirection()).andReturn();
        mvc.perform(get("/duffel").session(session).flashAttrs(submitted.getFlashMap()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("123.45 EUR")))
                .andExpect(content().string(containsString("total for 2 adult(s)")))
                .andExpect(content().string(containsString("Operating &lt;Airline&gt;")))
                .andExpect(content().string(containsString("Return Airline")))
                .andExpect(content().string(not(containsString("bookingUrl"))));
        mvc.perform(get("/duffel").session(session)).andExpect(status().isOk());
        verify(client, times(1)).search(any());
    }

    @Test void apiReturnsRateLimitAndRejectsInvalidInputs() throws Exception {
        MockHttpSession session = session();
        String csrf = (String) session.getAttribute("duffelCsrf");
        mvc.perform(post("/api/duffel/search").session(session).header("X-CSRF-Token", csrf)
                        .contentType("application/json").content("{\"adults\":0}"))
                .andExpect(status().isBadRequest());
        verify(client, never()).search(any());
        when(client.search(any())).thenThrow(new DuffelException(429, "Local allowance reached."));
        mvc.perform(post("/api/duffel/search").session(session).header("X-CSRF-Token", csrf)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isTooManyRequests()).andExpect(jsonPath("$.error").value("Local allowance reached."));
    }

    private MockHttpSession session() throws Exception {
        return (MockHttpSession) mvc.perform(get("/duffel")).andReturn().getRequest().getSession();
    }
}
