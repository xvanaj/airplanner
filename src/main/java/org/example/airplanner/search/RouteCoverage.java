package org.example.airplanner.search;

import org.example.airplanner.model.Airline;

import java.util.List;

/** Which other airlines fly a route we found a fare for. */
@FunctionalInterface
public interface RouteCoverage {

    RouteCoverage NONE = (origin, destination) -> List.of();

    List<Airline> alsoFlying(String origin, String destination);
}
