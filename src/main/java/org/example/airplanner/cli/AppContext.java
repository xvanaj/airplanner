package org.example.airplanner.cli;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.client.WizzAirClient;
import org.example.airplanner.model.Airline;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.FlightSearchService;
import org.example.airplanner.search.RouteCoverage;
import org.example.airplanner.search.WizzAirRoutes;

import java.io.PrintStream;
import java.time.Duration;
import java.util.Set;

/** Wires the client, the airport directory and the search service together for one command run. */
public class AppContext implements AutoCloseable {

    private final HttpJsonClient http;
    private final RyanairClient client;
    private final boolean quiet;
    private AirportDirectory directory;
    private int progressWidth;

    public AppContext(GlobalOptions global, String market) {
        this.http = HttpJsonClient.withDefaults(!global.noCache);
        this.client = new RyanairClient(http, market, Duration.ofMinutes(Math.max(0, global.cacheTtlMinutes)));
        this.quiet = global.quiet;
    }

    public HttpJsonClient http() {
        return http;
    }

    public RyanairClient client() {
        return client;
    }

    public synchronized AirportDirectory directory() {
        if (directory == null) {
            progress("loading Ryanair airport list");
            directory = AirportDirectory.load(client);
        }
        return directory;
    }

    public FlightSearchService searchService(int maxRefine) {
        return new FlightSearchService(client, directory(), http, maxRefine);
    }

    /**
     * Route coverage for the airlines we cannot price but can still tell you about.
     * Ryanair needs nothing here - its fares are the results themselves.
     */
    public RouteCoverage coverage(Set<Airline> airlines) {
        if (!airlines.contains(Airline.WIZZ_AIR)) {
            return RouteCoverage.NONE;
        }
        progress("loading the Wizz Air route map");
        return new WizzAirRoutes(new WizzAirClient(http));
    }

    /** Progress goes to stderr so that piping stdout to a file stays clean. */
    public FlightSearchService.Progress progress() {
        return this::progress;
    }

    public synchronized void progress(String message) {
        if (quiet) {
            return;
        }
        PrintStream err = System.err;
        String line = "  " + message;
        err.print("\r" + line + " ".repeat(Math.max(0, progressWidth - line.length())));
        err.flush();
        progressWidth = line.length();
    }

    public synchronized void clearProgress() {
        if (quiet || progressWidth == 0) {
            return;
        }
        System.err.print("\r" + " ".repeat(progressWidth) + "\r");
        System.err.flush();
        progressWidth = 0;
    }

    @Override
    public void close() {
        clearProgress();
        http.close();
    }
}
