package org.example.airplanner.cli;

import org.example.airplanner.model.Airline;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.SortBy;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.util.Dates;
import picocli.CommandLine.Option;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** The options shared by every command that actually searches for flights. */
public class SearchOptions {

    @Option(names = {"-f", "--from"}, split = ",", paramLabel = "AIRPORT", required = true,
            description = "Origin airports. IATA codes, city or country names: DUB,STN or 'vienna,bratislava'.")
    public List<String> origins = new ArrayList<>();

    @Option(names = {"-t", "--to"}, split = ",", paramLabel = "AIRPORT",
            description = "Destination airports, cities, countries, regions, or a named group such as "
                    + "\"greek islands\" or \"canaries\" (see 'airplanner airports --groups'). "
                    + "Omit to search everywhere Ryanair flies from your origins.")
    public List<String> destinations = new ArrayList<>();

    @Option(names = {"-d", "--depart-from"}, paramLabel = "DATE",
            description = "First possible outbound date (YYYY-MM-DD, today, +14d). Default: tomorrow.")
    public String departFrom;

    @Option(names = {"-D", "--depart-to"}, paramLabel = "DATE",
            description = "Last possible outbound date. Default: 60 days out.")
    public String departTo;

    @Option(names = "--month", paramLabel = "YYYY-MM",
            description = "Shorthand for a whole month as the outbound window.")
    public String month;

    @Option(names = "--out-time", paramLabel = "WINDOW", defaultValue = "any",
            description = "When the outbound flight may leave: HH:mm-HH:mm or "
                    + "night/early/morning/afternoon/evening/business/afterwork. Default: any.")
    public String outTime;

    @Option(names = "--back-time", paramLabel = "WINDOW", defaultValue = "any",
            description = "When the return flight may leave. Same formats as --out-time.")
    public String backTime;

    @Option(names = "--out-days", paramLabel = "DAYS", defaultValue = "any",
            description = "Weekdays the outbound may leave on: fri,sat or weekend/weekdays/any.")
    public String outDays;

    @Option(names = "--back-days", paramLabel = "DAYS", defaultValue = "any",
            description = "Weekdays the return may leave on.")
    public String backDays;

    @Option(names = "--nights", paramLabel = "N|MIN-MAX", defaultValue = "1-7",
            description = "Nights away, e.g. 3 or 2-5. Use 0 to allow same-day returns. Default: 1-7.")
    public String nights;

    @Option(names = "--one-way", description = "Search outbound flights only.")
    public boolean oneWay;

    @Option(names = "--weekend",
            description = "Preset: leave Fri/Sat, come back Sun/Mon, 1-3 nights.")
    public boolean weekend;

    @Option(names = "--budget", paramLabel = "AMOUNT",
            description = "Maximum price per adult for the whole trip.")
    public Double budget;

    @Option(names = "--min-stay", paramLabel = "DURATION",
            description = "Minimum time on the ground at the destination, e.g. 36h or 2d.")
    public String minStay;

    @Option(names = {"-c", "--currency"}, defaultValue = "EUR", description = "Currency code. Default: EUR.")
    public String currency;

    @Option(names = {"-p", "--passengers"}, defaultValue = "1",
            description = "Adults travelling. Prices stay per adult; a party total column is added.")
    public int passengers;

    @Option(names = {"-n", "--limit"}, defaultValue = "25", description = "Maximum trips to show. Default: 25.")
    public int limit;

    @Option(names = "--per-route", defaultValue = "3",
            description = "Maximum trips kept per origin-destination pair, so one cheap route "
                    + "cannot fill the list. Default: 3.")
    public int perRoute;

    @Option(names = "--sort", defaultValue = "PRICE",
            description = "Order results by: ${COMPLETION-CANDIDATES}. Default: PRICE.")
    public SortBy sort;

    @Option(names = "--airlines", split = ",", paramLabel = "AIRLINE", defaultValue = "ryanair,wizzair",
            description = "Airlines to consider: ryanair, wizzair. Default: both. Only Ryanair publishes "
                    + "fares; Wizz Air contributes route coverage, flagged in an ALSO column.")
    public List<String> airlines = new ArrayList<>();

    @Option(names = "--market", defaultValue = "en-gb", description = "Ryanair market code. Default: en-gb.")
    public String market;

    /** The airlines the user asked for, resolved. */
    public Set<Airline> selectedAirlines() {
        Set<Airline> selected = EnumSet.noneOf(Airline.class);
        for (String token : airlines) {
            for (String part : token.split(",")) {
                if (!part.isBlank()) {
                    selected.add(Airline.parse(part));
                }
            }
        }
        return selected;
    }

    @Option(names = "--fast",
            description = "Skip the per-day refinement pass. Faster and fewer requests, but a day whose "
                    + "cheapest flight sits outside your time window is dropped rather than re-checked.")
    public boolean fast;

    @Option(names = "--max-refine", defaultValue = "400",
            description = "Cap on refinement requests for a single search. Default: 400.")
    public int maxRefine;

    public TimeWindow outboundWindow() {
        return TimeWindow.parse(outTime);
    }

    public TimeWindow inboundWindow() {
        return TimeWindow.parse(backTime);
    }

    public SearchCriteria toCriteria(AirportDirectory directory) {
        LocalDate from;
        LocalDate to;
        if (month != null && !month.isBlank()) {
            YearMonth ym = Dates.parseMonth(month);
            from = ym.atDay(1);
            to = ym.atEndOfMonth();
        } else {
            from = Dates.parse(departFrom, LocalDate.now().plusDays(1));
            to = Dates.parse(departTo, from.plusDays(60));
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("The last outbound date " + to + " is before the first one " + from);
        }

        DayFilter outDayFilter = DayFilter.parse(outDays);
        DayFilter backDayFilter = DayFilter.parse(backDays);
        int[] nightRange = Dates.parseRange(nights, new int[]{1, 7});
        if (weekend) {
            if ("any".equalsIgnoreCase(outDays)) {
                outDayFilter = DayFilter.parse("fri,sat");
            }
            if ("any".equalsIgnoreCase(backDays)) {
                backDayFilter = DayFilter.parse("sun,mon");
            }
            if ("1-7".equals(nights)) {
                nightRange = new int[]{1, 3};
            }
        }

        return SearchCriteria.builder()
                .origins(directory.resolveCodes(origins))
                .destinations(directory.resolveCodes(destinations))
                .departFrom(from)
                .departTo(to)
                .outboundTime(outboundWindow())
                .inboundTime(inboundWindow())
                .outboundDays(outDayFilter)
                .inboundDays(backDayFilter)
                .minNights(nightRange[0])
                .maxNights(nightRange[1])
                .oneWay(oneWay)
                .maxPrice(budget == null ? null : Money.of(budget, currency))
                .minTimeAtDestination(Dates.parseDuration(minStay))
                .currency(currency.toUpperCase(java.util.Locale.ROOT))
                .passengers(Math.max(1, passengers))
                .limit(limit)
                .perRoute(perRoute)
                .sort(sort)
                .fast(fast)
                .build();
    }
}
