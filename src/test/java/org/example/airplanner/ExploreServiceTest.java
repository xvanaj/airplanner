package org.example.airplanner;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.*;
import org.example.airplanner.search.*;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ExploreServiceTest {
    private final LocalDate date = LocalDate.now().plusDays(10);
    private final RyanairClient client = mock(RyanairClient.class);
    private final FlightSearchService service = new FlightSearchService(client,
            new AirportDirectory(List.of()), mock(HttpJsonClient.class), 100);

    private SearchCriteria.Builder criteria() {
        return SearchCriteria.builder().origins(List.of("DUB"))
                .departFrom(date).departTo(date.plusDays(5)).minNights(1).maxNights(3);
    }

    private RyanairClient.RoundTripFare fare(String destination, int offset, int nights, int returnHour) {
        LocalDate day = date.plusDays(offset);
        return new RyanairClient.RoundTripFare(
                new RyanairClient.FareLeg("DUB", destination, day.atTime(10, 0), day.atTime(12, 0),
                        "FR1", 20, "EUR"),
                new RyanairClient.FareLeg(destination, "DUB", day.plusDays(nights).atTime(returnHour, 0),
                        day.plusDays(nights).atTime(returnHour + 2, 0), "FR2", 15, "EUR"));
    }

    private void returns(RyanairClient.RoundTripFare... fares) {
        when(client.cheapestRoundTripAnywhere(anyString(), any(), any(), any(), any(), any(), any(),
                any(), any(), anyInt(), anyInt(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of(fares));
    }

    @Test
    void appliesMinimumStayToAnywhereResults() {
        returns(fare("STN", 0, 1, 9), fare("BCN", 0, 2, 9));
        var trips = service.explore(criteria().minTimeAtDestination(Duration.ofHours(36)).build(),
                FlightSearchService.Progress.NONE);
        assertEquals(List.of("BCN"), trips.stream().map(Trip::destinationCode).toList());
    }

    @Test
    void validatesDateTimeAndNightsEvenIfUpstreamIgnoresFilters() {
        returns(fare("STN", 0, 1, 9), fare("STN", 0, 1, 18),
                fare("STN", 0, 4, 9), fare("STN", 6, 1, 9));
        var trips = service.explore(criteria().inboundTime(TimeWindow.parse("morning")).build(),
                FlightSearchService.Progress.NONE);
        assertEquals(1, trips.size());
        assertEquals(date, trips.getFirst().outbound().date());
        assertEquals(1, trips.getFirst().nights());
    }

    @Test
    void balancesRoutesButDoesNotCapASingleRoute() {
        returns(fare("STN", 0, 1, 9), fare("STN", 1, 1, 9), fare("BCN", 0, 1, 9));
        assertEquals(2, service.explore(criteria().perRoute(1).build(), FlightSearchService.Progress.NONE).size());
        returns(fare("STN", 0, 1, 9), fare("STN", 1, 1, 9));
        assertEquals(2, service.explore(criteria().perRoute(1).build(), FlightSearchService.Progress.NONE).size());
    }

    @Test
    void rejectsReturnBeforeAnOvernightArrival() {
        var out = new RyanairClient.FareLeg("DUB", "STN", date.atTime(23, 0),
                date.plusDays(1).atTime(3, 0), "FR1", 20, "EUR");
        var back = new RyanairClient.FareLeg("STN", "DUB", date.plusDays(1).atTime(2, 0),
                date.plusDays(1).atTime(4, 0), "FR2", 15, "EUR");
        returns(new RyanairClient.RoundTripFare(out, back));
        assertTrue(service.explore(criteria().build(), FlightSearchService.Progress.NONE).isEmpty());
    }
}
