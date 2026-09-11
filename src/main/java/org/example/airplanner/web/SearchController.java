package org.example.airplanner.web;

import jakarta.servlet.http.HttpServletRequest;
import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.Airline;
import org.example.airplanner.model.Airport;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.SortBy;
import org.example.airplanner.output.ResultFormatter;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.AirportGroups;
import org.example.airplanner.search.RouteCoverage;
import org.example.airplanner.search.WizzAirRoutes;
import org.example.airplanner.search.FlightSearchService;
import org.example.airplanner.search.SearchResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;


/** The one page, plus CSV and JSON of the same query. */
@Controller
public class SearchController {

    private final AirportCatalog catalog;
    private final RyanairClient client;
    private final HttpJsonClient http;
    private final AirplannerProperties properties;
    private final WizzAirRoutes wizzAirRoutes;

    public SearchController(AirportCatalog catalog, RyanairClient client, HttpJsonClient http,
                            AirplannerProperties properties, WizzAirRoutes wizzAirRoutes) {
        this.catalog = catalog;
        this.client = client;
        this.http = http;
        this.properties = properties;
        this.wizzAirRoutes = wizzAirRoutes;
    }

    @GetMapping("/")
    public String index(@ModelAttribute("form") SearchForm form, HttpServletRequest request, Model model) {
        model.addAttribute("weekdays", DayOfWeek.values());
        model.addAttribute("sorts", SortBy.values());
        // Group names are static, so the quick picks render even before the airport list loads.
        model.addAttribute("groups", AirportGroups.all());
        model.addAttribute("originPicks", originPicks());
        model.addAttribute("airlines", Airline.values());
        model.addAttribute("queryString", request.getQueryString() == null ? "" : request.getQueryString());
        if (!form.hasQuery()) {
            return "index";
        }
        Set<Airline> selected = form.selectedAirlines();
        if (selected.stream().noneMatch(Airline::hasFares)) {
            model.addAttribute("error", "Tick at least one airline whose fares can be read - "
                    + "currently that means Ryanair.");
            return "index";
        }
        try {
            AirportDirectory directory = catalog.directory();
            SearchResult result = run(form, directory);
            RouteCoverage coverage = coverage(selected);
            List<TripView> rows = new ArrayList<>();
            int index = 1;
            for (var trip : result.trips()) {
                rows.add(TripView.of(index++, trip, directory, coverage));
            }
            model.addAttribute("result", result);
            model.addAttribute("rows", rows);
            model.addAttribute("notes", result.notes().stream().distinct().limit(8).toList());
            model.addAttribute("searched", true);
            model.addAttribute("wizzSelected", selected.contains(Airline.WIZZ_AIR));
            model.addAttribute("anywhere", result.criteria().anywhere());
            model.addAttribute("elapsedSeconds",
                    String.format(Locale.ENGLISH, "%.1f", result.elapsed().toMillis() / 1000.0));
            model.addAttribute("cheapest", rows.isEmpty() ? null : rows.stream()
                    .min((a, b) -> Double.compare(amount(a.price()), amount(b.price()))).orElse(null));
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
        } catch (RuntimeException e) {
            model.addAttribute("error", "Ryanair did not answer as expected: " + e.getMessage());
        }
        return "index";
    }

    @GetMapping(value = "/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public ResponseEntity<String> api(@ModelAttribute SearchForm form) {
        AirportDirectory directory = catalog.directory();
        SearchResult result = run(form, directory);
        return ResponseEntity.ok(new ResultFormatter(directory).json(result));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @ResponseBody
    public ResponseEntity<String> csv(@ModelAttribute SearchForm form) {
        AirportDirectory directory = catalog.directory();
        SearchResult result = run(form, directory);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"ryanair-trips.csv\"")
                .body(new ResultFormatter(directory).csv(result));
    }

    /** Airport list for the form's autocomplete, as JSON. */
    @GetMapping(value = "/api/airports", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<AirportView> airports() {
        return catalog.forPicker().stream()
                .map(a -> new AirportView(a.code(), a.name(), a.cityName(), a.countryName()))
                .toList();
    }

    /** The named destination groups, with the airports each currently covers. */
    @GetMapping(value = "/api/groups", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<GroupView> groups() {
        AirportDirectory directory = catalog.directory();
        return directory.groups().stream()
                .map(g -> new GroupView(g.name(), g.aliases(), g.description(),
                        directory.inGroup(g).stream().map(Airport::code).sorted().toList()))
                .toList();
    }

    /**
     * The configured home airports, labelled from the live directory. If Ryanair has
     * stopped flying from one - or the directory cannot be loaded - the chip says so
     * instead of silently disappearing.
     */
    private List<PickView> originPicks() {
        AirportDirectory directory;
        try {
            directory = catalog.directory();
        } catch (RuntimeException e) {
            directory = null;
        }
        List<PickView> picks = new ArrayList<>();
        for (String raw : properties.getHomeAirports()) {
            String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            if (code.isEmpty()) {
                continue;
            }
            if (directory == null) {
                picks.add(new PickView(code, code, code, true));
                continue;
            }
            picks.add(directory.byCode(code)
                    .map(a -> new PickView(code,
                            a.cityName() == null || a.cityName().isBlank() ? a.name() : a.cityName(),
                            code + " - " + a.name() + ", " + a.countryName(),
                            true))
                    .orElseGet(() -> new PickView(code, code,
                            "Ryanair does not currently fly from " + code, false)));
        }
        return picks;
    }

    /** An airport as the picker needs it. */
    public record AirportView(String code, String name, String city, String country) {
    }

    /** One quick-pick chip: what it puts in the field, what it says, and whether it is usable. */
    public record PickView(String value, String label, String title, boolean available) {
    }

    /** A named group as the picker needs it. */
    public record GroupView(String name, List<String> aliases, String description, List<String> airports) {
    }

    /**
     * Route coverage for the ticked airlines we cannot price. Built per request, but the
     * underlying route map is fetched once and cached like any other response.
     */
    private RouteCoverage coverage(Set<Airline> selected) {
        return selected.contains(Airline.WIZZ_AIR) ? wizzAirRoutes : RouteCoverage.NONE;
    }

    private SearchResult run(SearchForm form, AirportDirectory directory) {
        SearchCriteria criteria = form.toCriteria(directory);
        FlightSearchService service =
                new FlightSearchService(client, directory, http, properties.getMaxRefine());
        // With no destination given, the full scan would walk every route from every origin.
        // Ryanair's own "anywhere" endpoint answers that question in one request per origin.
        return criteria.anywhere()
                ? service.exploreResult(criteria, FlightSearchService.Progress.NONE)
                : service.search(criteria, FlightSearchService.Progress.NONE);
    }

    private static double amount(String money) {
        try {
            return Double.parseDouble(money.split(" ")[0]);
        } catch (RuntimeException e) {
            return Double.MAX_VALUE;
        }
    }
}
