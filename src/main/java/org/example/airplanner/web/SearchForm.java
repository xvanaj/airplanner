package org.example.airplanner.web;

import org.example.airplanner.model.Airline;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.SortBy;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.util.Dates;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The form on the page. Everything is a string or a primitive so that it round-trips
 * through the query string, which keeps a result page shareable and bookmarkable.
 */
public class SearchForm {

    private String from = "";
    private String to = "";
    private String departFrom = LocalDate.now().plusDays(1).toString();
    private String departTo = LocalDate.now().plusDays(60).toString();
    private List<String> outDays = new ArrayList<>();
    private List<String> backDays = new ArrayList<>();
    // Civilised hours by default: nobody opens this page hoping for a 05:40 departure.
    // The "any" preset next to each pair widens it back to 00:00-23:59.
    private String outTimeFrom = "08:00";
    private String outTimeTo = "22:00";
    private String backTimeFrom = "08:00";
    private String backTimeTo = "22:00";
    private int minNights = 1;
    private int maxNights = 7;
    private boolean oneWay;
    private String budget = "";
    private String minStay = "";
    private String currency = "EUR";
    private int passengers = 1;
    private int limit = 30;
    private int perRoute = 3;
    private SortBy sort = SortBy.PRICE;
    private boolean fast;
    private List<String> airlines = new ArrayList<>(List.of(Airline.RYANAIR.name(), Airline.WIZZ_AIR.name()));

    /**
     * The airlines ticked on the page. The form always posts one empty value, so an
     * untouched page and a page with everything unticked stay distinguishable.
     */
    public Set<Airline> selectedAirlines() {
        Set<Airline> selected = EnumSet.noneOf(Airline.class);
        for (String token : airlines) {
            Airline.find(token).ifPresent(selected::add);
        }
        return selected;
    }

    /** True once the user has actually asked for a search rather than just opened the page. */
    public boolean hasQuery() {
        return from != null && !from.isBlank();
    }

    public SearchCriteria toCriteria(AirportDirectory directory) {
        LocalDate first = Dates.parse(departFrom, LocalDate.now().plusDays(1));
        LocalDate last = Dates.parse(departTo, first.plusDays(60));
        if (last.isBefore(first)) {
            throw new IllegalArgumentException("The last outbound date is before the first one.");
        }
        List<String> origins = directory.resolveCodes(split(from));
        if (origins.isEmpty()) {
            throw new IllegalArgumentException("Give at least one airport to fly from.");
        }
        return SearchCriteria.builder()
                .origins(origins)
                .destinations(directory.resolveCodes(split(to)))
                .departFrom(first)
                .departTo(last)
                .outboundTime(window(outTimeFrom, outTimeTo))
                .inboundTime(window(backTimeFrom, backTimeTo))
                .outboundDays(days(outDays))
                .inboundDays(days(backDays))
                .minNights(Math.max(0, minNights))
                .maxNights(Math.max(Math.max(0, minNights), maxNights))
                .oneWay(oneWay)
                .maxPrice(budget == null || budget.isBlank()
                        ? null : Money.of(parseAmount(budget), currency.toUpperCase(Locale.ROOT)))
                .minTimeAtDestination(minStay == null || minStay.isBlank() ? null : Dates.parseDuration(minStay))
                .currency(currency == null || currency.isBlank() ? "EUR" : currency.toUpperCase(Locale.ROOT))
                .passengers(Math.max(1, passengers))
                .limit(Math.clamp(limit, 1, 500))
                .perRoute(Math.max(1, perRoute))
                .sort(sort == null ? SortBy.PRICE : sort)
                .fast(fast)
                .build();
    }

    private static double parseAmount(String raw) {
        try {
            return Double.parseDouble(raw.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a price.");
        }
    }

    private static List<String> split(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split("[,;\n]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static DayFilter days(List<String> selected) {
        if (selected == null || selected.isEmpty()) {
            return DayFilter.ANY;
        }
        return DayFilter.parse(String.join(",", selected));
    }

    private static TimeWindow window(String fromTime, String toTime) {
        LocalTime start = parseTime(fromTime, LocalTime.MIDNIGHT);
        LocalTime end = parseTime(toTime, LocalTime.of(23, 59));
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("A departure window ends before it starts (" + fromTime
                    + " to " + toTime + ").");
        }
        return new TimeWindow(start, end);
    }

    private static LocalTime parseTime(String raw, LocalTime fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return LocalTime.parse(raw.trim().length() == 5 ? raw.trim() : raw.trim().substring(0, 5));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("'" + raw + "' is not a time of day (use HH:mm).");
        }
    }

    // ---------------------------------------------------------------- accessors

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }

    public String getDepartFrom() {
        return departFrom;
    }

    public void setDepartFrom(String departFrom) {
        this.departFrom = departFrom;
    }

    public String getDepartTo() {
        return departTo;
    }

    public void setDepartTo(String departTo) {
        this.departTo = departTo;
    }

    public List<String> getOutDays() {
        return outDays;
    }

    public void setOutDays(List<String> outDays) {
        this.outDays = outDays;
    }

    public List<String> getBackDays() {
        return backDays;
    }

    public void setBackDays(List<String> backDays) {
        this.backDays = backDays;
    }

    public String getOutTimeFrom() {
        return outTimeFrom;
    }

    public void setOutTimeFrom(String outTimeFrom) {
        this.outTimeFrom = outTimeFrom;
    }

    public String getOutTimeTo() {
        return outTimeTo;
    }

    public void setOutTimeTo(String outTimeTo) {
        this.outTimeTo = outTimeTo;
    }

    public String getBackTimeFrom() {
        return backTimeFrom;
    }

    public void setBackTimeFrom(String backTimeFrom) {
        this.backTimeFrom = backTimeFrom;
    }

    public String getBackTimeTo() {
        return backTimeTo;
    }

    public void setBackTimeTo(String backTimeTo) {
        this.backTimeTo = backTimeTo;
    }

    public int getMinNights() {
        return minNights;
    }

    public void setMinNights(int minNights) {
        this.minNights = minNights;
    }

    public int getMaxNights() {
        return maxNights;
    }

    public void setMaxNights(int maxNights) {
        this.maxNights = maxNights;
    }

    public boolean isOneWay() {
        return oneWay;
    }

    public void setOneWay(boolean oneWay) {
        this.oneWay = oneWay;
    }

    public String getBudget() {
        return budget;
    }

    public void setBudget(String budget) {
        this.budget = budget;
    }

    public String getMinStay() {
        return minStay;
    }

    public void setMinStay(String minStay) {
        this.minStay = minStay;
    }

    public String getCurrency() {
        return currency;
    }

    public void setCurrency(String currency) {
        this.currency = currency;
    }

    public int getPassengers() {
        return passengers;
    }

    public void setPassengers(int passengers) {
        this.passengers = passengers;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public int getPerRoute() {
        return perRoute;
    }

    public void setPerRoute(int perRoute) {
        this.perRoute = perRoute;
    }

    public SortBy getSort() {
        return sort;
    }

    public void setSort(SortBy sort) {
        this.sort = sort;
    }

    public boolean isFast() {
        return fast;
    }

    public void setFast(boolean fast) {
        this.fast = fast;
    }

    public List<String> getAirlines() {
        return airlines;
    }

    public void setAirlines(List<String> airlines) {
        this.airlines = airlines == null ? new ArrayList<>() : new ArrayList<>(airlines);
    }
}
