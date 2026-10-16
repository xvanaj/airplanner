package org.example.airplanner.web;

import org.example.airplanner.model.Flight;
import org.example.airplanner.model.Trip;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.RouteCoverage;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * One result row, pre-formatted so the template stays free of logic.
 */
public record TripView(int index,
                       String origin,
                       String originName,
                       String destination,
                       String destinationName,
                       String outDate,
                       String outTimes,
                       String outDuration,
                       String inDate,
                       String inTimes,
                       String inDuration,
                       int nights,
                       String stay,
                       String price,
                       String total,
                       String perNight,
                       String link,
                       String compareLink,
                       AccommodationLinks accommodation,
                       boolean returnTrip,
                       List<AlsoOn> alsoOn) {

    /**
     * Another airline that flies this route, and where to price it.
     */
    public record AlsoOn(String code, String airline, String link) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);

    public static TripView of(int index, Trip trip, AirportDirectory directory, RouteCoverage coverage,
                              String stay22Aid, int stayAdults, List<Integer> stayChildAges) {
        Flight out = trip.outbound();
        Flight back = trip.inbound();
        List<AlsoOn> alsoOn = coverage.alsoFlying(trip.originCode(), trip.destinationCode()).stream()
                .map(airline -> new AlsoOn(airline.code(), airline.displayName(), trip.searchUrlOn(airline)))
                .toList();
        return new TripView(
                index,
                out.origin(),
                directory.describe(out.origin()),
                out.destination(),
                directory.describe(out.destination()),
                out.date().format(DAY),
                times(out),
                out.durationText(),
                back == null ? "" : back.date().format(DAY),
                back == null ? "" : times(back),
                back == null ? "" : back.durationText(),
                trip.nights(),
                stay(trip.timeAtDestination()),
                money(trip.pricePerAdult().amount().toPlainString(), trip.pricePerAdult().currency()),
                money(trip.totalForParty().amount().toPlainString(), trip.pricePerAdult().currency()),
                money(trip.pricePerNight().amount().toPlainString(), trip.pricePerAdult().currency()),
                trip.bookingUrl(),
                trip.compareUrl(),
                AccommodationLinks.forTrip(trip, directory, stay22Aid, stayAdults, stayChildAges),
                trip.isReturn(),
                alsoOn);
    }

    private static String times(Flight flight) {
        String arrival = flight.arrival() == null ? "" : " - " + flight.arrival().format(TIME);
        return flight.departure().format(TIME) + arrival;
    }

    private static String stay(Duration duration) {
        if (duration == null) {
            return "";
        }
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        return days > 0 ? days + "d " + hours + "h" : duration.toHours() + "h";
    }

    private static String money(String amount, String currency) {
        return amount + " " + currency;
    }
}
