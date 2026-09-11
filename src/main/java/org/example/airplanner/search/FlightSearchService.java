package org.example.airplanner.search;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.Flight;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.model.Trip;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Searches every origin against every destination and assembles the trips that fit.
 *
 * <p>The scan is deliberately two-phased so that a wide search stays cheap:
 * <ol>
 *   <li>one "cheapest fare per day" request per route and month gives a full calendar
 *       of prices and departure times;</li>
 *   <li>only for days whose cheapest flight falls outside the requested time window is
 *       a second, day-specific request made, asking Ryanair for the best fare
 *       <em>inside</em> the window.</li>
 * </ol>
 * That keeps a broad search to a handful of requests per route while still being exact
 * about time-of-day constraints.
 */
public class FlightSearchService {

    /** Progress callback so a CLI can show what is happening during a long scan. */
    public interface Progress {
        void update(String message);

        Progress NONE = message -> { };
    }

    private final RyanairClient client;
    private final AirportDirectory directory;
    private final HttpJsonClient http;
    private final int maxRefineRequests;

    public FlightSearchService(RyanairClient client, AirportDirectory directory, HttpJsonClient http,
                               int maxRefineRequests) {
        this.client = client;
        this.directory = directory;
        this.http = http;
        this.maxRefineRequests = maxRefineRequests;
    }

    public SearchResult search(SearchCriteria criteria) {
        return search(criteria, Progress.NONE);
    }

    public SearchResult search(SearchCriteria criteria, Progress progress) {
        long startedAt = System.nanoTime();
        int callsBefore = http.networkCalls();
        int hitsBefore = http.cacheHits();
        List<String> notes = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger refineBudget = new AtomicInteger(maxRefineRequests);
        AtomicInteger skipped = new AtomicInteger();

        List<String[]> pairs = routePairs(criteria, notes, skipped, progress);
        Map<String, List<Trip>> byRoute = new ConcurrentHashMap<>();
        AtomicInteger done = new AtomicInteger();

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> tasks = new ArrayList<>();
            for (String[] pair : pairs) {
                tasks.add(pool.submit(() -> {
                    String origin = pair[0];
                    String destination = pair[1];
                    try {
                        List<Trip> trips = searchRoute(origin, destination, criteria, refineBudget, notes);
                        if (!trips.isEmpty()) {
                            byRoute.put(origin + "-" + destination, trips);
                        }
                    } catch (RuntimeException e) {
                        notes.add(origin + "-" + destination + ": " + e.getMessage());
                    } finally {
                        progress.update("scanned " + done.incrementAndGet() + "/" + pairs.size()
                                + " routes (" + origin + "-" + destination + ")");
                    }
                }));
            }
            for (Future<?> task : tasks) {
                try {
                    task.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Search interrupted", e);
                } catch (ExecutionException e) {
                    notes.add("A route scan failed: " + e.getCause().getMessage());
                }
            }
        }

        if (refineBudget.get() <= 0) {
            notes.add("Hit the refinement request limit (" + maxRefineRequests
                    + "); some days inside your time window may be missing. Raise it with --max-refine.");
        }

        List<Trip> trips = selectTrips(byRoute.values().stream().flatMap(List::stream).toList(), criteria);

        return new SearchResult(criteria, trips, List.copyOf(notes), pairs.size(), skipped.get(),
                http.networkCalls() - callsBefore, http.cacheHits() - hitsBefore,
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    private List<Trip> selectTrips(List<Trip> candidates, SearchCriteria criteria) {
        Comparator<Trip> order = criteria.sort().comparator()
                .thenComparing(Trip::originCode).thenComparing(Trip::destinationCode);
        Map<String, List<Trip>> byRoute = candidates.stream()
                .filter(t -> matchesCriteria(t, criteria))
                .collect(Collectors.groupingBy(t -> t.outbound().route()));
        // The per-route cap keeps one cheap city from filling the whole list; with a single
        // route there is nothing to balance, so it would only hide results.
        int perRoute = byRoute.size() > 1 ? Math.max(1, criteria.perRoute()) : Integer.MAX_VALUE;
        return byRoute.values().stream()
                .flatMap(list -> list.stream().sorted(order).limit(perRoute))
                .sorted(order)
                .limit(Math.max(1, criteria.limit()))
                .toList();

    }

    private boolean matchesCriteria(Trip trip, SearchCriteria criteria) {
        Flight out = trip.outbound();
        if (!criteria.origins().contains(trip.originCode())
                || (!criteria.anywhere() && !criteria.destinations().contains(trip.destinationCode()))
                || out.date().isBefore(LocalDate.now())
                || out.date().isBefore(criteria.departFrom()) || out.date().isAfter(criteria.departTo())
                || !criteria.outboundDays().allows(out.date().getDayOfWeek())
                || !criteria.outboundTime().contains(out.departureTime()) || !withinBudget(trip, criteria)) {
            return false;
        }
        if (criteria.oneWay()) {
            return !trip.isReturn();
        }
        if (!trip.isReturn() || !trip.inbound().destination().equals(trip.originCode())
                || !trip.inbound().origin().equals(trip.destinationCode())
                || trip.nights() < criteria.minNights() || trip.nights() > criteria.maxNights()
                || !criteria.inboundDays().allows(trip.inbound().date().getDayOfWeek())
                || !criteria.inboundTime().contains(trip.inbound().departureTime())) {
            return false;
        }
        if (out.arrival() != null && !trip.inbound().departure().isAfter(out.arrival())) {
            return false;
        }
        Duration stay = trip.timeAtDestination();
        return criteria.minTimeAtDestination() == null
                || (stay != null && stay.compareTo(criteria.minTimeAtDestination()) >= 0);
    }

    // ---------------------------------------------------------------- route selection

    private List<String[]> routePairs(SearchCriteria criteria, List<String> notes, AtomicInteger skipped,
                                      Progress progress) {
        List<String[]> pairs = new ArrayList<>();
        for (String origin : criteria.origins()) {
            progress.update("loading routes from " + origin);
            Set<String> served;
            try {
                served = client.destinationsFrom(origin);
            } catch (RuntimeException e) {
                notes.add("Could not load the route map for " + origin + ": " + e.getMessage());
                served = Set.of();
            }
            List<String> wanted = criteria.anywhere() ? new ArrayList<>(served) : criteria.destinations();
            for (String destination : wanted) {
                if (destination.equalsIgnoreCase(origin)) {
                    continue;
                }
                if (!served.isEmpty() && !served.contains(destination)) {
                    skipped.incrementAndGet();
                    notes.add("No direct Ryanair route " + origin + "-" + destination + ", skipped.");
                    continue;
                }
                pairs.add(new String[]{origin, destination});
            }
        }
        return pairs;
    }

    // ---------------------------------------------------------------- one route

    private List<Trip> searchRoute(String origin, String destination, SearchCriteria criteria,
                                   AtomicInteger refineBudget, List<String> notes) {
        List<Flight> outbound = legCandidates(origin, destination, criteria.departFrom(), criteria.departTo(),
                criteria.outboundTime(), criteria.outboundDays(), criteria, refineBudget);
        if (outbound.isEmpty()) {
            return List.of();
        }
        if (criteria.oneWay()) {
            return outbound.stream()
                    .map(f -> new Trip(f, null, f.price(), criteria.passengers()))
                    .filter(t -> withinBudget(t, criteria))
                    .toList();
        }

        List<Flight> inbound = legCandidates(destination, origin, criteria.returnFrom(), criteria.returnTo(),
                criteria.inboundTime(), criteria.inboundDays(), criteria, refineBudget);
        if (inbound.isEmpty()) {
            return List.of();
        }
        return combine(outbound, inbound, criteria);
    }

    private List<Trip> combine(List<Flight> outbound, List<Flight> inbound, SearchCriteria criteria) {
        Map<LocalDate, Flight> inboundByDay = new LinkedHashMap<>();
        for (Flight f : inbound) {
            inboundByDay.merge(f.date(), f, (a, b) -> a.price().compareTo(b.price()) <= 0 ? a : b);
        }
        List<Trip> trips = new ArrayList<>();
        for (Flight out : outbound) {
            for (int nights = criteria.minNights(); nights <= criteria.maxNights(); nights++) {
                Flight back = inboundByDay.get(out.date().plusDays(nights));
                if (back == null) {
                    continue;
                }
                if (out.arrival() != null && !back.departure().isAfter(out.arrival())) {
                    continue;
                }
                Trip trip = new Trip(out, back, out.price().plus(back.price()), criteria.passengers());
                if (!withinBudget(trip, criteria)) {
                    continue;
                }
                Duration stay = trip.timeAtDestination();
                if (criteria.minTimeAtDestination() != null
                        && (stay == null || stay.compareTo(criteria.minTimeAtDestination()) < 0)) {
                    continue;
                }
                trips.add(trip);
            }
        }
        return trips;
    }

    private boolean withinBudget(Trip trip, SearchCriteria criteria) {
        return criteria.maxPrice() == null || trip.pricePerAdult().compareTo(criteria.maxPrice()) <= 0;
    }

    // ---------------------------------------------------------------- one leg

    /**
     * All the days in the range on which this sector can be flown inside the requested
     * window, each with the cheapest fare found for that day.
     */
    private List<Flight> legCandidates(String from, String to, LocalDate rangeStart, LocalDate rangeEnd,
                                       TimeWindow window, DayFilter days, SearchCriteria criteria,
                                       AtomicInteger refineBudget) {
        LocalDate start = rangeStart.isBefore(LocalDate.now()) ? LocalDate.now() : rangeStart;
        if (rangeEnd.isBefore(start)) {
            return List.of();
        }
        List<Flight> flights = new ArrayList<>();
        List<LocalDate> needsRefinement = new ArrayList<>();

        for (YearMonth month = YearMonth.from(start); !month.isAfter(YearMonth.from(rangeEnd));
             month = month.plusMonths(1)) {
            for (RyanairClient.DayFare fare : client.cheapestPerDay(from, to, month, criteria.currency())) {
                LocalDate day = fare.day();
                if (day.isBefore(start) || day.isAfter(rangeEnd) || !days.allows(day.getDayOfWeek())) {
                    continue;
                }
                if (!fare.available() || fare.departure() == null) {
                    continue;
                }
                if (window.contains(fare.departure().toLocalTime())) {
                    flights.add(flight(from, to, fare));
                } else if (!criteria.fast() && !window.isAny()) {
                    // A flight exists that day but leaves outside the window; ask for the
                    // cheapest one that does fit.
                    needsRefinement.add(day);
                }
            }
        }

        for (LocalDate day : needsRefinement) {
            if (refineBudget.decrementAndGet() < 0) {
                break;
            }
            Optional<RyanairClient.FareLeg> leg = client.cheapestOneWay(from, to, day, day, window,
                    DayFilter.ANY, criteria.currency(), criteria.passengers());
            leg.filter(l -> window.contains(l.departure().toLocalTime()))
                    .ifPresent(l -> flights.add(flight(from, to, l)));
        }

        flights.sort(Comparator.comparing(Flight::departure));
        return flights;
    }

    private Flight flight(String from, String to, RyanairClient.DayFare fare) {
        return Flight.of(from, to, fare.departure(), fare.arrival(), null,
                Money.of(fare.price(), fare.currency()), directory.zoneOf(from), directory.zoneOf(to));
    }

    private Flight flight(String from, String to, RyanairClient.FareLeg leg) {
        return Flight.of(from, to, leg.departure(), leg.arrival(), leg.flightNumber(),
                Money.of(leg.price(), leg.currency()), directory.zoneOf(from), directory.zoneOf(to));
    }

    // ---------------------------------------------------------------- extras

    /** {@link #explore} wrapped in the same result envelope a full search returns. */
    public SearchResult exploreResult(SearchCriteria criteria, Progress progress) {
        long startedAt = System.nanoTime();
        int callsBefore = http.networkCalls();
        int hitsBefore = http.cacheHits();
        List<Trip> trips = explore(criteria, progress);
        int routes = (int) trips.stream().map(t -> t.originCode() + t.destinationCode()).distinct().count();
        return new SearchResult(criteria, trips, List.of(), routes, 0,
                http.networkCalls() - callsBefore, http.cacheHits() - hitsBefore,
                Duration.ofNanos(System.nanoTime() - startedAt));
    }

    /** Cheapest destination per origin, using Ryanair's own "anywhere" endpoints. */
    public List<Trip> explore(SearchCriteria criteria, Progress progress) {
        List<Trip> trips = new ArrayList<>();
        for (String origin : criteria.origins()) {
            progress.update("exploring destinations from " + origin);
            if (criteria.oneWay()) {
                for (RyanairClient.FareLeg leg : client.cheapestOneWayAnywhere(origin, criteria.departFrom(),
                        criteria.departTo(), criteria.outboundTime(), criteria.outboundDays(),
                        criteria.currency(), criteria.passengers(), 200)) {
                    Flight out = flight(leg.origin(), leg.destination(), leg);
                    trips.add(new Trip(out, null, out.price(), criteria.passengers()));
                }
            } else {
                for (RyanairClient.RoundTripFare fare : client.cheapestRoundTripAnywhere(origin,
                        criteria.departFrom(), criteria.departTo(), criteria.returnFrom(), criteria.returnTo(),
                        criteria.outboundTime(), criteria.inboundTime(), criteria.outboundDays(),
                        criteria.inboundDays(), criteria.minNights(), criteria.maxNights(),
                        criteria.currency(), criteria.passengers(), 200)) {
                    if (fare.inbound() == null) {
                        continue;
                    }
                    Flight out = flight(fare.outbound().origin(), fare.outbound().destination(), fare.outbound());
                    Flight back = flight(fare.inbound().origin(), fare.inbound().destination(), fare.inbound());
                    trips.add(new Trip(out, back, out.price().plus(back.price()), criteria.passengers()));
                }
            }
        }
        return selectTrips(trips, criteria);
    }
}
