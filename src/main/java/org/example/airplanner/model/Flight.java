package org.example.airplanner.model;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * A single Ryanair sector with the cheapest fare found for it.
 * Departure and arrival are wall-clock times at their respective airports, which is
 * how Ryanair publishes them; {@link #duration()} is the real elapsed time and
 * therefore needs both airports' time zones.
 */
public record Flight(String origin,
                     String destination,
                     LocalDateTime departure,
                     LocalDateTime arrival,
                     String flightNumber,
                     Money price,
                     Duration duration) {

    public static Flight of(String origin, String destination, LocalDateTime departure, LocalDateTime arrival,
                            String flightNumber, Money price, ZoneId originZone, ZoneId destinationZone) {
        Duration duration = null;
        if (departure != null && arrival != null && originZone != null && destinationZone != null) {
            ZonedDateTime dep = departure.atZone(originZone);
            ZonedDateTime arr = arrival.atZone(destinationZone);
            Duration d = Duration.between(dep, arr);
            duration = d.isNegative() ? null : d;
        }
        return new Flight(origin, destination, departure, arrival, flightNumber, price, duration);
    }

    public LocalDate date() {
        return departure.toLocalDate();
    }

    public LocalTime departureTime() {
        return departure.toLocalTime();
    }

    public String route() {
        return origin + "-" + destination;
    }

    public String durationText() {
        if (duration == null) {
            return "-";
        }
        return duration.toHours() + "h" + String.format("%02d", duration.toMinutesPart());
    }
}
