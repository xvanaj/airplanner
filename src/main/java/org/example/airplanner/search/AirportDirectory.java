package org.example.airplanner.search;

import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.Airport;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The list of airports Ryanair serves, plus the lookup that turns what a user typed
 * ("DUB", "london", "spain") into concrete IATA codes.
 */
public class AirportDirectory {

    private final Map<String, Airport> byCode = new LinkedHashMap<>();

    public AirportDirectory(List<Airport> airports) {
        airports.stream()
                .sorted(Comparator.comparing(Airport::code))
                .forEach(a -> byCode.put(a.code().toUpperCase(Locale.ROOT), a));
    }

    public static AirportDirectory load(RyanairClient client) {
        return new AirportDirectory(client.activeAirports());
    }

    public Optional<Airport> byCode(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(code.trim().toUpperCase(Locale.ROOT)));
    }

    public ZoneId zoneOf(String code) {
        return byCode(code).map(Airport::zone).orElse(ZoneOffset.UTC);
    }

    public String describe(String code) {
        return byCode(code).map(a -> a.name() + ", " + a.countryName()).orElse(code);
    }

    public List<Airport> all() {
        return List.copyOf(byCode.values());
    }

    /**
     * Resolves one user token to airports. Accepts an IATA code, a named group such as
     * {@code greek islands}, an airport, city, region or country name, or the explicit
     * forms {@code country:IE}, {@code city:LONDON}, {@code region:sicily} and
     * {@code group:canaries}. Matching is case-insensitive and, for names, a substring
     * match.
     */
    public List<Airport> resolve(String token) {
        String raw = token == null ? "" : token.trim();
        if (raw.isEmpty()) {
            return List.of();
        }
        if (raw.regionMatches(true, 0, "country:", 0, 8)) {
            return byCountry(raw.substring(8));
        }
        if (raw.regionMatches(true, 0, "city:", 0, 5)) {
            return byCity(raw.substring(5));
        }
        if (raw.regionMatches(true, 0, "region:", 0, 7)) {
            return byRegion(raw.substring(7));
        }
        if (raw.regionMatches(true, 0, "group:", 0, 6)) {
            String name = raw.substring(6);
            return AirportGroups.find(name)
                    .map(this::inGroup)
                    .orElseThrow(() -> new IllegalArgumentException("No airport group called '" + name
                            + "'. Known groups: " + groupNames() + "."));
        }
        Optional<Airport> exact = byCode(raw);
        if (exact.isPresent()) {
            return List.of(exact.get());
        }
        Optional<List<Airport>> group = AirportGroups.find(raw).map(this::inGroup);
        if (group.isPresent() && !group.get().isEmpty()) {
            return group.get();
        }
        String needle = raw.toLowerCase(Locale.ROOT);
        List<Airport> cityOrAirport = byCode.values().stream()
                .filter(a -> matches(a.cityName(), needle) || matches(a.name(), needle))
                .toList();
        if (!cityOrAirport.isEmpty()) {
            return cityOrAirport;
        }
        List<Airport> regions = byRegion(raw);
        if (!regions.isEmpty()) {
            return regions;
        }
        List<Airport> countries = byCountry(raw);
        if (!countries.isEmpty()) {
            return countries;
        }
        throw new IllegalArgumentException("No Ryanair airport matches '" + token
                + "'. Try an IATA code (DUB), a city (london), a country (spain) or a group ("
                + groupNames() + ").");
    }

    /** The airports of a named group that Ryanair currently serves. */
    public List<Airport> inGroup(AirportGroups.Group group) {
        return byCode.values().stream().filter(group::matches).toList();
    }

    /** Every group that currently resolves to at least one airport. */
    public List<AirportGroups.Group> groups() {
        return AirportGroups.all().stream().filter(g -> !inGroup(g).isEmpty()).toList();
    }

    private String groupNames() {
        return AirportGroups.all().stream().map(AirportGroups.Group::name).collect(Collectors.joining(", "));
    }

    /** Resolves a comma separated list of tokens, keeping the order and dropping duplicates. */
    public List<String> resolveCodes(List<String> tokens) {
        Set<String> codes = new LinkedHashSet<>();
        for (String token : tokens) {
            for (String part : token.split(",")) {
                if (!part.isBlank()) {
                    resolve(part).forEach(a -> codes.add(a.code()));
                }
            }
        }
        return new ArrayList<>(codes);
    }

    private List<Airport> byCountry(String name) {
        String needle = name.trim().toLowerCase(Locale.ROOT);
        return byCode.values().stream()
                .filter(a -> a.countryCode().equalsIgnoreCase(needle) || matches(a.countryName(), needle))
                .toList();
    }

    private List<Airport> byCity(String name) {
        String needle = name.trim().toLowerCase(Locale.ROOT);
        return byCode.values().stream()
                .filter(a -> matches(a.cityName(), needle))
                .toList();
    }

    private List<Airport> byRegion(String name) {
        String needle = name.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty() || needle.startsWith("default")) {
            return List.of();
        }
        return byCode.values().stream()
                .filter(a -> matches(a.regionName(), needle))
                .toList();
    }

    private static boolean matches(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** Airports within {@code radiusKm} of the given one - handy for "which base can I drive to". */
    public List<Airport> near(Airport centre, double radiusKm) {
        return byCode.values().stream()
                .filter(a -> !a.code().equals(centre.code()))
                .filter(a -> a.distanceKmTo(centre) <= radiusKm)
                .sorted(Comparator.comparingDouble(a -> a.distanceKmTo(centre)))
                .toList();
    }
}
