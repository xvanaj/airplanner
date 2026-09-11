package org.example.airplanner.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Wizz Air's published route network.
 *
 * <p>Only the network: their fare endpoints ({@code /search/timetable},
 * {@code /search/search}) reject anything that is not their own browser - 400 with an
 * empty body, or 429 on the first call - so there are no Wizz prices to be had here.
 * The route map is open, and knowing that Wizz flies a route you are pricing on Ryanair
 * is worth the one request it costs.
 *
 * <p>The API version sits in the URL and changes every few weeks, so it is discovered
 * from the Wizz Air home page rather than pinned.
 */
public class WizzAirClient {

    private static final String HOME_PAGE = "https://wizzair.com/en-gb";
    private static final String FALLBACK_API = "https://be.wizzair.com/29.15.1/Api";
    private static final Pattern API_BASE = Pattern.compile("https://be\\.wizzair\\.com/\\d+\\.\\d+\\.\\d+/Api");
    private static final Duration STATIC_TTL = Duration.ofHours(24);

    private final HttpJsonClient http;
    private volatile String apiBase;

    public WizzAirClient(HttpJsonClient http) {
        this.http = http;
    }

    /** The current API root, e.g. {@code https://be.wizzair.com/29.15.1/Api}. */
    public String apiBase() {
        String known = apiBase;
        if (known != null) {
            return known;
        }
        String discovered = FALLBACK_API;
        try {
            Matcher matcher = API_BASE.matcher(http.getText(HOME_PAGE, STATIC_TTL));
            if (matcher.find()) {
                discovered = matcher.group();
            }
        } catch (RuntimeException e) {
            // Keep the fallback; a stale version still answers for a while.
        }
        apiBase = discovered;
        return discovered;
    }

    /**
     * Every route Wizz Air flies, as origin IATA to the set of destinations. One request,
     * cached for a day.
     */
    public Map<String, Set<String>> routeMap() {
        JsonNode root = http.get(apiBase() + "/asset/map?languageCode=en-gb", STATIC_TTL);
        Map<String, Set<String>> routes = new LinkedHashMap<>();
        for (JsonNode city : root.path("cities")) {
            String origin = city.path("iata").asText(null);
            if (origin == null) {
                continue;
            }
            Set<String> destinations = routes.computeIfAbsent(origin, k -> new LinkedHashSet<>());
            for (JsonNode connection : city.path("connections")) {
                String destination = connection.path("iata").asText(null);
                if (destination != null) {
                    destinations.add(destination);
                }
            }
        }
        return routes;
    }
}
