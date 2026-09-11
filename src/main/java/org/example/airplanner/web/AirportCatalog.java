package org.example.airplanner.web;

import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.Airport;
import org.example.airplanner.search.AirportDirectory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * Holds the airport directory for the web app. It is loaded on first use rather than at
 * startup, so the app still boots when Ryanair (or the network) is unreachable.
 */
@Component
public class AirportCatalog {

    private final RyanairClient client;
    private volatile AirportDirectory directory;

    public AirportCatalog(RyanairClient client) {
        this.client = client;
    }

    public AirportDirectory directory() {
        AirportDirectory local = directory;
        if (local == null) {
            synchronized (this) {
                local = directory;
                if (local == null) {
                    local = AirportDirectory.load(client);
                    directory = local;
                }
            }
        }
        return local;
    }

    /** Airports for the form's autocomplete list, ordered by country then city. */
    public List<Airport> forPicker() {
        return directory().all().stream()
                .sorted(Comparator.comparing(Airport::countryName).thenComparing(Airport::name))
                .toList();
    }
}
