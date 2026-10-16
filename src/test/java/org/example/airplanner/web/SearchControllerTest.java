package org.example.airplanner.web;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.Airport;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.WizzAirRoutes;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Set;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Renders the page without touching the network: the Ryanair client is stubbed out. */
@WebMvcTest(SearchController.class)
class SearchControllerTest {

    private static final AirportDirectory DIRECTORY = new AirportDirectory(List.of(
            new Airport("DUB", "Dublin", "Dublin", "Leinster", "Ireland", "ie", 53.4, -6.2, "Europe/Dublin", true),
            new Airport("STN", "London Stansted", "London", "England", "United Kingdom", "gb", 51.9, 0.2,
                    "Europe/London", true),
            new Airport("VIE", "Vienna", "Vienna", "Default AT", "Austria", "at", 48.1, 16.6,
                    "Europe/Vienna", true)));

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AirportCatalog catalog;

    @MockitoBean
    private RyanairClient client;

    @MockitoBean
    private HttpJsonClient http;

    @MockitoBean
    private AirplannerProperties properties;

    @MockitoBean
    private WizzAirRoutes wizzAirRoutes;

    @BeforeEach
    void stubRyanair() {
        given(catalog.directory()).willReturn(DIRECTORY);
        given(catalog.forPicker()).willReturn(DIRECTORY.all());
        given(client.destinationsFrom(anyString())).willReturn(Set.of("STN"));
        given(client.cheapestPerDay(anyString(), anyString(), any(), anyString())).willReturn(List.of());
        given(properties.getMaxRefine()).willReturn(50);
        given(properties.getStayAdults()).willReturn(2);
        given(properties.getStayChildAges()).willReturn(List.of(2, 5, 7));
        given(properties.getHomeAirports()).willReturn(List.of("VIE", "BRQ"));
    }

    @Test
    void showsTheEmptyFormWithoutSearching() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Fly from")))
                .andExpect(content().string(not(containsString("Nothing matched"))));
    }

    @Test
    void reportsWhenNothingMatches() throws Exception {
        mvc.perform(get("/").param("from", "DUB").param("to", "STN")
                        .param("departFrom", "2026-11-01").param("departTo", "2026-11-30"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Nothing matched")));
    }

    @Test
    void rendersAccommodationChoicesWithoutAnAffiliateAccount() throws Exception {
        given(client.cheapestPerDay(anyString(), anyString(), any(), anyString())).willReturn(List.of(
                new RyanairClient.DayFare(LocalDate.of(2026, 11, 1),
                        LocalDateTime.of(2026, 11, 1, 10, 0), LocalDateTime.of(2026, 11, 1, 12, 0),
                        42.0, "EUR", true)));
        mvc.perform(get("/").param("from", "DUB").param("to", "STN")
                        .param("departFrom", "2026-11-01").param("departTo", "2026-11-01")
                        .param("oneWay", "true").param("airlines", "RYANAIR"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("https://www.airbnb.com/s/homes?query=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("https://www.booking.com/searchresults.html?ss=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("https://www.expedia.com/Hotel-Search?destination=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("https://www.hotels.com/Hotel-Search?destination=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("https://www.vrbo.com/search?destination=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("https://www.agoda.com/search?textToSearch=London%2C+United+Kingdom")))
                .andExpect(content().string(containsString("Find accommodation in London, United Kingdom")))
                .andExpect(content().string(containsString("2 adults · 3 children (ages 2, 5, 7)")))
                .andExpect(content().string(containsString("stay-provider provider-bookingcom")))
                .andExpect(content().string(containsString("group_children=3&amp;age=2&amp;age=5&amp;age=7")))
                .andExpect(content().string(containsString("Choose stay dates")))
                .andExpect(content().string(not(containsString("www.stay22.com"))));
    }

    @Test
    void explainsAnUnknownAirport() throws Exception {
        mvc.perform(get("/").param("from", "Atlantis"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No Ryanair airport matches")));
    }

    @Test
    void offersTheHomeAirportsAsChips() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                // Served: labelled with its city and tickable.
                .andExpect(content().string(containsString("class=\"pick-origin\" value=\"VIE\"")))
                .andExpect(content().string(containsString("<span>Vienna</span>")))
                // Not in the directory: shown, explained and disabled rather than dropped.
                .andExpect(content().string(containsString("Ryanair does not currently fly from BRQ")))
                .andExpect(content().string(containsString("class=\"pick-origin\" value=\"BRQ\" disabled")));
    }

    @Test
    void offersBothAirlinesTickedByDefault() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"airlines\" value=\"RYANAIR\" checked")))
                .andExpect(content().string(containsString("name=\"airlines\" value=\"WIZZ_AIR\" checked")))
                .andExpect(content().string(containsString("Wizz Air (routes only)")));
    }

    @Test
    void refusesToSearchWithNoPriceableAirline() throws Exception {
        mvc.perform(get("/").param("from", "DUB").param("to", "STN").param("airlines", "WIZZ_AIR"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Tick at least one airline whose fares can be read")));
    }

    @Test
    void servesTheAirportListForAutocomplete() throws Exception {
        mvc.perform(get("/api/airports"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"code\":\"DUB\"")));
    }

    @Test
    void exportsCsvForTheSameQuery() throws Exception {
        mvc.perform(get("/export.csv").param("from", "DUB").param("to", "STN"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("origin,destination")));
    }
}
