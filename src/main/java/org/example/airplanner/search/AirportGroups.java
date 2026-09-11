package org.example.airplanner.search;

import org.example.airplanner.model.Airport;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Named sets of destinations you can type instead of a list of codes - "greek islands"
 * rather than CFU,CHQ,EFL,HER,JMK,JSI,JTR,KGS,LXS,RHO,ZTH.
 *
 * <p>Most groups are a filter over Ryanair's own airport data (it tags Corfu, Rhodes and
 * friends with the region "Greek Islands"), so a group follows the airline: an island
 * added next summer joins the group on its own. Codes are only listed by hand where the
 * published region is not usable - Palma, for instance, is filed under "Default ES".
 */
public final class AirportGroups {

    /** One named set of airports. */
    public record Group(String name, List<String> aliases, String description, Predicate<Airport> matcher) {

        public boolean matches(Airport airport) {
            return matcher.test(airport);
        }

        /** Every name this group answers to. */
        public List<String> allNames() {
            return java.util.stream.Stream.concat(java.util.stream.Stream.of(name), aliases.stream()).toList();
        }
    }

    private static final Predicate<Airport> GREEK_ISLANDS = region("Greek Islands");
    private static final Predicate<Airport> CANARIES = region("Canary Isles");
    private static final Predicate<Airport> BALEARICS = codes("PMI", "IBZ", "MAH");
    private static final Predicate<Airport> ITALIAN_ISLANDS = region("Sicily", "Sardinia").or(codes("OLB"));

    private static final List<Group> ALL = List.of(
            new Group("greek islands",
                    List.of("greek isles", "greek island", "greece islands", "islands of greece"),
                    "Every Greek island Ryanair flies to",
                    GREEK_ISLANDS),
            new Group("canary islands",
                    List.of("canaries", "canary isles", "canarias"),
                    "Lanzarote, Fuerteventura, Gran Canaria, Tenerife",
                    CANARIES),
            new Group("balearic islands",
                    List.of("balearics", "baleares"),
                    "Mallorca, Ibiza, Menorca",
                    BALEARICS),
            new Group("italian islands",
                    List.of("islands of italy"),
                    "Sicily and Sardinia",
                    ITALIAN_ISLANDS),
            new Group("spanish islands",
                    List.of("islands of spain"),
                    "The Canaries and the Balearics",
                    CANARIES.or(BALEARICS)),
            new Group("mediterranean islands",
                    List.of("med islands"),
                    "Greek, Italian and Balearic islands, Malta and Cyprus",
                    GREEK_ISLANDS.or(ITALIAN_ISLANDS).or(BALEARICS).or(codes("MLA", "LCA", "PFO"))),
            new Group("atlantic islands",
                    List.of(),
                    "The Canaries and Madeira",
                    CANARIES.or(codes("FNC"))),
            new Group("winter sun",
                    List.of("warm", "warm winter", "sun"),
                    "As close to the tropics as these airlines get: Canaries, Madeira, Morocco, "
                            + "Cyprus, Jordan",
                    CANARIES.or(codes("FNC")).or(country("ma")).or(codes("AMM", "AQJ", "LCA", "PFO"))));

    private AirportGroups() {
    }

    public static List<Group> all() {
        return ALL;
    }

    /** Looks a group up by its name or any alias, ignoring case and extra whitespace. */
    public static Optional<Group> find(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String needle = normalise(token);
        return ALL.stream()
                .filter(g -> g.allNames().stream().anyMatch(name -> normalise(name).equals(needle)))
                .findFirst();
    }

    private static String normalise(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[\\s_-]+", " ");
    }

    private static Predicate<Airport> region(String... regions) {
        Set<String> wanted = Arrays.stream(regions).map(AirportGroups::normalise).collect(Collectors.toSet());
        return airport -> airport.regionName() != null && wanted.contains(normalise(airport.regionName()));
    }

    private static Predicate<Airport> country(String... countryCodes) {
        Set<String> wanted = Arrays.stream(countryCodes).map(c -> c.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return airport -> airport.countryCode() != null
                && wanted.contains(airport.countryCode().toLowerCase(Locale.ROOT));
    }

    private static Predicate<Airport> codes(String... codes) {
        Set<String> wanted = Arrays.stream(codes).map(c -> c.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        return airport -> wanted.contains(airport.code().toUpperCase(Locale.ROOT));
    }
}
