package org.example.airplanner.duffel;

import java.time.LocalDate;
import java.util.Locale;

/** One route and exact dates; deliberately cannot expand into a calendar scan. */
public record OfferSearch(String origin, String destination, LocalDate departureDate,
                          LocalDate returnDate, int adults, int maxConnections) {
    public OfferSearch {
        origin = airport(origin);
        destination = airport(destination);
        if (origin.equals(destination)) throw new IllegalArgumentException("Choose different airports.");
        if (departureDate == null || departureDate.isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("Choose an outbound date today or later.");
        }
        if (returnDate != null && returnDate.isBefore(departureDate)) {
            throw new IllegalArgumentException("Return date cannot be before departure.");
        }
        if (adults < 1 || adults > 9) throw new IllegalArgumentException("Choose 1 to 9 adults.");
        if (maxConnections < 0 || maxConnections > 2) {
            throw new IllegalArgumentException("Choose 0 to 2 connections per direction.");
        }
    }

    private static String airport(String value) {
        String code = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z]{3}")) throw new IllegalArgumentException("Enter a three-letter IATA airport code.");
        return code;
    }
}
