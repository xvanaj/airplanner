package org.example.airplanner.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;

/** An outbound flight plus, for return trips, the matching inbound flight. */
public record Trip(Flight outbound, Flight inbound, Money pricePerAdult, int passengers) {

    public boolean isReturn() {
        return inbound != null;
    }

    /** Nights spent at the destination; 0 for a day trip or a one-way. */
    public int nights() {
        if (inbound == null) {
            return 0;
        }
        return (int) ChronoUnit.DAYS.between(outbound.date(), inbound.date());
    }

    /** Time between landing at the destination and taking off again. */
    public Duration timeAtDestination() {
        if (inbound == null || outbound.arrival() == null || inbound.departure() == null) {
            return null;
        }
        Duration d = Duration.between(outbound.arrival(), inbound.departure());
        return d.isNegative() ? null : d;
    }

    public Money totalForParty() {
        return pricePerAdult.times(Math.max(passengers, 1));
    }

    public Money pricePerNight() {
        return pricePerAdult.dividedBy(Math.max(nights(), 1));
    }

    public String destinationCode() {
        return outbound.destination();
    }

    public String originCode() {
        return outbound.origin();
    }

    /** Deep link that opens this exact date pair pre-filled on ryanair.com. */
    public String bookingUrl() {
        return Airline.RYANAIR.searchUrl(outbound.origin(), outbound.destination(),
                outbound.date(), inbound == null ? null : inbound.date(), passengers);
    }

    /** The same route and dates on another airline's own site. */
    public String searchUrlOn(Airline airline) {
        return airline.searchUrl(outbound.origin(), outbound.destination(),
                outbound.date(), inbound == null ? null : inbound.date(), passengers);
    }

    /**
     * A neutral search for the same route and dates. easyJet, Eurowings, Vueling, Pegasus
     * and Smartwings all block automated access to their fares, so this is the one honest
     * way to see what they charge: hand the exact trip to a search engine that has them.
     */
    public String compareUrl() {
        String query = "Flights from " + outbound.origin() + " to " + outbound.destination()
                + " on " + outbound.date()
                + (inbound == null ? " one way" : " through " + inbound.date());
        return "https://www.google.com/travel/flights?q="
                + URLEncoder.encode(query, StandardCharsets.UTF_8);
    }
}
