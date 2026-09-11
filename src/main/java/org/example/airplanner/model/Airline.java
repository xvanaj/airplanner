package org.example.airplanner.model;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * An airline this tool knows about, and how much of it we can actually read.
 *
 * <p>{@link #hasFares()} is the honest bit: Ryanair publishes the endpoints its own site
 * calls, so its prices are here. Wizz Air's fare API answers 400/429 to anything that is
 * not their browser, so only its route network is readable - enough to tell you that a
 * route exists and to send you to their site to price it.
 */
public enum Airline {

    RYANAIR("FR", "Ryanair", true),
    WIZZ_AIR("W6", "Wizz Air", false);

    private final String code;
    private final String displayName;
    private final boolean fares;

    Airline(String code, String displayName, boolean fares) {
        this.code = code;
        this.displayName = displayName;
        this.fares = fares;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether this tool can read prices for this airline, or only its route map. */
    public boolean hasFares() {
        return fares;
    }

    public static List<Airline> withFares() {
        return Arrays.stream(values()).filter(Airline::hasFares).toList();
    }

    /** Accepts "ryanair", "FR", "wizz", "wizzair", "wizz air", "W6". */
    public static Optional<Airline> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String needle = token.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
        return Arrays.stream(values())
                .filter(a -> a.name().toLowerCase(Locale.ROOT).replace("_", "").equals(needle)
                        || a.code.toLowerCase(Locale.ROOT).equals(needle)
                        || a.displayName.toLowerCase(Locale.ROOT).replace(" ", "").equals(needle)
                        || needle.equals("wizz") && a == WIZZ_AIR)
                .findFirst();
    }

    public static Airline parse(String token) {
        return find(token).orElseThrow(() -> new IllegalArgumentException("Unknown airline '" + token
                + "'. Known airlines: " + Arrays.stream(values()).map(a -> a.name().toLowerCase(Locale.ROOT))
                .toList() + "."));
    }

    /** A link to this airline's own search, pre-filled with the route and dates. */
    public String searchUrl(String origin, String destination, LocalDate outbound, LocalDate inbound, int adults) {
        int pax = Math.max(1, adults);
        return switch (this) {
            case RYANAIR -> "https://www.ryanair.com/gb/en/trip/flights/select?"
                    + "adults=" + pax + "&teens=0&children=0&infants=0"
                    + "&dateOut=" + enc(outbound.toString())
                    + (inbound == null ? "" : "&dateIn=" + enc(inbound.toString()))
                    + "&isConnectedFlight=false&discount=0"
                    + "&isReturn=" + (inbound != null)
                    + "&originIata=" + enc(origin)
                    + "&destinationIata=" + enc(destination);
            case WIZZ_AIR -> "https://wizzair.com/en-gb/booking/select-flight/"
                    + enc(origin) + "/" + enc(destination) + "/"
                    + outbound + "/" + (inbound == null ? "null" : inbound.toString())
                    + "/" + pax + "/0/0/null";
        };
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
