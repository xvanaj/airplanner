package org.example.airplanner.cli;

import org.example.airplanner.model.Airport;
import org.example.airplanner.output.Table;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.AirportGroups;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "airports",
        description = "Look up airports, the routes they serve, destination groups, and the "
                + "destinations several origins share.",
        sortOptions = false,
        footer = {
                "",
                "Examples:",
                "  airplanner airports london",
                "  airplanner airports greek islands",
                "  airplanner airports --groups",
                "  airplanner airports --routes VIE",
                "  airplanner airports --common VIE,BTS,BUD",
                "  airplanner airports --near VIE --radius 250"
        })
public class AirportsCommand implements Callable<Integer> {

    @Parameters(arity = "0..*", paramLabel = "QUERY",
            description = "Airport, city, country, region or group to look for.")
    List<String> query = new ArrayList<>();

    @Option(names = "--routes", paramLabel = "AIRPORT", description = "List every destination served from here.")
    String routes;

    @Option(names = "--common", split = ",", paramLabel = "AIRPORT",
            description = "Destinations reachable from all of these origins - the useful list when a "
                    + "group is flying from different home airports.")
    List<String> common = new ArrayList<>();

    @Option(names = "--near", paramLabel = "AIRPORT", description = "Other airports close to this one.")
    String near;

    @Option(names = "--groups",
            description = "List the named destination groups, such as 'greek islands', and what they cover.")
    boolean groups;

    @Option(names = "--radius", defaultValue = "200", description = "Radius in km for --near. Default: 200.")
    double radius;

    @Mixin
    GlobalOptions global = new GlobalOptions();

    @Override
    public Integer call() {
        try (AppContext ctx = new AppContext(global, "en-gb")) {
            AirportDirectory directory = ctx.directory();
            ctx.clearProgress();
            if (groups) {
                return printGroups(directory);
            }
            if (routes != null) {
                return printRoutes(ctx, directory);
            }
            if (!common.isEmpty()) {
                return printCommon(ctx, directory);
            }
            if (near != null) {
                Airport centre = directory.byCode(near)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown airport " + near));
                print(directory.near(centre, radius), directory, centre);
                return 0;
            }
            List<Airport> matches = query.isEmpty()
                    ? directory.all()
                    : directory.resolveCodes(asTokens(directory)).stream()
                            .map(code -> directory.byCode(code).orElseThrow())
                            .toList();
            print(matches, directory, null);
            return matches.isEmpty() ? 1 : 0;
        }
    }

    /**
     * "airports greek islands" arrives as two words. Treat the whole phrase as one token
     * when it resolves, and as separate lookups when it does not.
     */
    private List<String> asTokens(AirportDirectory directory) {
        String phrase = String.join(" ", query);
        try {
            if (!directory.resolve(phrase).isEmpty()) {
                return List.of(phrase);
            }
        } catch (IllegalArgumentException e) {
            // Not a phrase; fall back to one lookup per word.
        }
        return query;
    }

    private int printGroups(AirportDirectory directory) {
        Table table = new Table().column("GROUP").column("ALSO KNOWN AS").column("COVERS")
                .column("N", Table.Align.RIGHT).column("AIRPORTS");
        for (AirportGroups.Group group : directory.groups()) {
            List<String> codes = directory.inGroup(group).stream().map(Airport::code).sorted().toList();
            table.row(group.name(), String.join(", ", group.aliases()), group.description(),
                    codes.size(), String.join(",", codes));
        }
        System.out.print(table.render());
        System.out.println();
        System.out.println("Use one anywhere an airport is expected, e.g. --to \"greek islands\".");
        return 0;
    }

    private int printRoutes(AppContext ctx, AirportDirectory directory) {
        String origin = directory.resolveCodes(List.of(routes)).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown airport " + routes));
        Set<String> destinations = ctx.client().destinationsFrom(origin);
        Table table = new Table().column("CODE").column("AIRPORT").column("COUNTRY");
        destinations.stream().sorted()
                .forEach(code -> directory.byCode(code).ifPresentOrElse(
                        a -> table.row(a.code(), a.name(), a.countryName()),
                        () -> table.row(code, "", "")));
        System.out.print(table.render());
        System.out.println();
        System.out.println(destinations.size() + " destinations from " + origin + ".");
        return destinations.isEmpty() ? 1 : 0;
    }

    private int printCommon(AppContext ctx, AirportDirectory directory) {
        List<String> origins = directory.resolveCodes(common);
        Set<String> shared = null;
        for (String origin : origins) {
            Set<String> served = ctx.client().destinationsFrom(origin);
            if (shared == null) {
                shared = new LinkedHashSet<>(served);
            } else {
                shared.retainAll(served);
            }
        }
        ctx.clearProgress();
        Table table = new Table().column("CODE").column("AIRPORT").column("COUNTRY");
        if (shared != null) {
            shared.stream().sorted().forEach(code -> directory.byCode(code)
                    .ifPresent(a -> table.row(a.code(), a.name(), a.countryName())));
        }
        System.out.print(table.render());
        System.out.println();
        System.out.println((shared == null ? 0 : shared.size()) + " destinations served from all of "
                + String.join(", ", origins) + ".");
        return table.size() == 0 ? 1 : 0;
    }

    private void print(List<Airport> airports, AirportDirectory directory, Airport centre) {
        Table table = new Table().column("CODE").column("AIRPORT").column("CITY").column("REGION")
                .column("COUNTRY").column("BASE");
        if (centre != null) {
            table.column("KM", Table.Align.RIGHT);
        }
        for (Airport a : airports) {
            if (centre == null) {
                table.row(a.code(), a.name(), a.cityName(), region(a), a.countryName(), a.base() ? "yes" : "");
            } else {
                table.row(a.code(), a.name(), a.cityName(), region(a), a.countryName(), a.base() ? "yes" : "",
                        Math.round(a.distanceKmTo(centre)));
            }
        }
        System.out.print(table.render());
        System.out.println();
        System.out.println(airports.size() + " airports.");
    }

    /** Ryanair files airports with no real region under "Default XX"; that helps nobody. */
    private static String region(Airport airport) {
        String region = airport.regionName();
        return region == null || region.toLowerCase(java.util.Locale.ROOT).startsWith("default") ? "" : region;
    }
}
