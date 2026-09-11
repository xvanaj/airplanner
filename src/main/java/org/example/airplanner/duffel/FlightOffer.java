package org.example.airplanner.duffel;

import org.example.airplanner.model.Money;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/** The provider's whole offer price; never synthesized from prices of individual segments. */
public record FlightOffer(String id, String provider, Money totalPrice, int adults,
                          Instant expiresAt, boolean liveMode, List<Journey> journeys) {
    public FlightOffer { journeys = List.copyOf(journeys); }
    public boolean expired() { return !expiresAt.isAfter(Instant.now()); }

    public record Journey(List<Segment> segments) {
        public Journey { segments = List.copyOf(segments); }
        public int connections() { return segments.size() - 1; }
    }

    public record Segment(String origin, String destination, LocalDateTime departure,
                          LocalDateTime arrival, String originTimeZone, String destinationTimeZone,
                          String operatingCarrier, String marketingCarrier, String flightNumber,
                          int stops) { }
}
