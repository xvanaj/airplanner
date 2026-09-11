package org.example.airplanner.search;

import org.example.airplanner.client.WizzAirClient;
import org.example.airplanner.model.Airline;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers "does Wizz Air fly this too?" from their published route map. Loaded once and
 * kept, because it is one request for the whole network.
 */
public class WizzAirRoutes implements RouteCoverage {

    private final WizzAirClient client;
    private volatile Map<String, Set<String>> routes;

    public WizzAirRoutes(WizzAirClient client) {
        this.client = client;
    }

    @Override
    public List<Airline> alsoFlying(String origin, String destination) {
        return covers(origin, destination) ? List.of(Airline.WIZZ_AIR) : List.of();
    }

    public boolean covers(String origin, String destination) {
        if (origin == null || destination == null) {
            return false;
        }
        Set<String> served = routeMap().get(origin.toUpperCase(Locale.ROOT));
        return served != null && served.contains(destination.toUpperCase(Locale.ROOT));
    }

    public Set<String> destinationsFrom(String origin) {
        return routeMap().getOrDefault(origin.toUpperCase(Locale.ROOT), Set.of());
    }

    private Map<String, Set<String>> routeMap() {
        Map<String, Set<String>> local = routes;
        if (local == null) {
            synchronized (this) {
                local = routes;
                if (local == null) {
                    try {
                        local = client.routeMap();
                    } catch (RuntimeException e) {
                        // Wizz being unreachable must not sink a Ryanair search.
                        local = Map.of();
                    }
                    routes = local;
                }
            }
        }
        return local;
    }
}
