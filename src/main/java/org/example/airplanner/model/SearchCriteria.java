package org.example.airplanner.model;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Everything the user asked for in one place. Build it with {@link #builder()}. */
public record SearchCriteria(List<String> origins,
                             List<String> destinations,
                             LocalDate departFrom,
                             LocalDate departTo,
                             TimeWindow outboundTime,
                             TimeWindow inboundTime,
                             DayFilter outboundDays,
                             DayFilter inboundDays,
                             int minNights,
                             int maxNights,
                             boolean oneWay,
                             Money maxPrice,
                             Duration minTimeAtDestination,
                             String currency,
                             int passengers,
                             int limit,
                             int perRoute,
                             SortBy sort,
                             boolean fast) {

    public SearchCriteria {
        origins = List.copyOf(origins);
        destinations = List.copyOf(destinations);
        if (departTo.isBefore(departFrom)) {
            throw new IllegalArgumentException("--to date " + departTo + " is before --from date " + departFrom);
        }
        if (minNights > maxNights) {
            throw new IllegalArgumentException("minimum nights " + minNights + " exceeds maximum " + maxNights);
        }
    }

    /** Earliest date an inbound flight could leave on. */
    public LocalDate returnFrom() {
        return departFrom.plusDays(minNights);
    }

    /** Latest date an inbound flight could leave on. */
    public LocalDate returnTo() {
        return departTo.plusDays(maxNights);
    }

    public boolean anywhere() {
        return destinations.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private List<String> origins = new ArrayList<>();
        private List<String> destinations = new ArrayList<>();
        private LocalDate departFrom = LocalDate.now();
        private LocalDate departTo = LocalDate.now().plusDays(60);
        private TimeWindow outboundTime = TimeWindow.ANY;
        private TimeWindow inboundTime = TimeWindow.ANY;
        private DayFilter outboundDays = DayFilter.ANY;
        private DayFilter inboundDays = DayFilter.ANY;
        private int minNights = 1;
        private int maxNights = 7;
        private boolean oneWay = false;
        private Money maxPrice;
        private Duration minTimeAtDestination;
        private String currency = "EUR";
        private int passengers = 1;
        private int limit = 25;
        private int perRoute = 3;
        private SortBy sort = SortBy.PRICE;
        private boolean fast = false;

        public Builder origins(List<String> v) { this.origins = v; return this; }
        public Builder destinations(List<String> v) { this.destinations = v; return this; }
        public Builder departFrom(LocalDate v) { this.departFrom = v; return this; }
        public Builder departTo(LocalDate v) { this.departTo = v; return this; }
        public Builder outboundTime(TimeWindow v) { this.outboundTime = v; return this; }
        public Builder inboundTime(TimeWindow v) { this.inboundTime = v; return this; }
        public Builder outboundDays(DayFilter v) { this.outboundDays = v; return this; }
        public Builder inboundDays(DayFilter v) { this.inboundDays = v; return this; }
        public Builder minNights(int v) { this.minNights = v; return this; }
        public Builder maxNights(int v) { this.maxNights = v; return this; }
        public Builder oneWay(boolean v) { this.oneWay = v; return this; }
        public Builder maxPrice(Money v) { this.maxPrice = v; return this; }
        public Builder minTimeAtDestination(Duration v) { this.minTimeAtDestination = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder passengers(int v) { this.passengers = v; return this; }
        public Builder limit(int v) { this.limit = v; return this; }
        public Builder perRoute(int v) { this.perRoute = v; return this; }
        public Builder sort(SortBy v) { this.sort = v; return this; }
        public Builder fast(boolean v) { this.fast = v; return this; }

        public SearchCriteria build() {
            return new SearchCriteria(origins, destinations, departFrom, departTo, outboundTime, inboundTime,
                    outboundDays, inboundDays, minNights, maxNights, oneWay, maxPrice, minTimeAtDestination,
                    currency, passengers, limit, perRoute, sort, fast);
        }
    }
}
