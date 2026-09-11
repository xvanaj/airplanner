package org.example.airplanner.model;

import java.util.Comparator;

/** How the result list is ordered. */
public enum SortBy {
    /** Cheapest first (the default). */
    PRICE(Comparator.comparing(Trip::pricePerAdult)),
    /** Cheapest per night at the destination first. */
    VALUE(Comparator.comparing(Trip::pricePerNight)),
    /** Soonest departure first. */
    DEPARTURE(Comparator.comparing((Trip t) -> t.outbound().departure())),
    /** Longest stay first. */
    LENGTH(Comparator.comparingInt(Trip::nights).reversed()),
    /** Grouped by destination, cheapest first inside each. */
    DESTINATION(Comparator.comparing(Trip::destinationCode).thenComparing(Trip::pricePerAdult));

    private final Comparator<Trip> comparator;

    SortBy(Comparator<Trip> comparator) {
        this.comparator = comparator;
    }

    public Comparator<Trip> comparator() {
        return comparator.thenComparing(t -> t.outbound().departure());
    }
}
