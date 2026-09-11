package org.example.airplanner.search;

import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.Trip;

import java.time.Duration;
import java.util.List;

/** What a search found, plus what happened while finding it. */
public record SearchResult(SearchCriteria criteria,
                           List<Trip> trips,
                           List<String> notes,
                           int routesScanned,
                           int routesSkipped,
                           int networkCalls,
                           int cacheHits,
                           Duration elapsed) {

    public boolean isEmpty() {
        return trips.isEmpty();
    }
}
