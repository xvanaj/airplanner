package org.example.airplanner.cli;

import org.example.airplanner.model.Airline;
import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.output.ResultFormatter;
import org.example.airplanner.search.RouteCoverage;
import org.example.airplanner.search.SearchResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "search",
        description = "Search return (or one-way) trips from several origins to several destinations.",
        sortOptions = false,
        footer = {
                "",
                "Examples:",
                "  airplanner search -f VIE,BTS -t BCN,AGP,NAP --month 2026-10 \\",
                "      --out-days fri,sat --out-time 15:00-23:59 --back-days sun,mon --back-time morning",
                "  airplanner search -f DUB -t STN --weekend --budget 60 --links",
                "  airplanner search -f VIE -t \"greek islands\" --month 2026-06 --nights 5-9 --budget 200",
                "  airplanner search -f 'vienna,bratislava' -t spain --nights 3-5 --format html -o trips.html"
        })
public class SearchCommand implements Callable<Integer> {

    @Mixin
    SearchOptions search = new SearchOptions();

    @Mixin
    OutputOptions output = new OutputOptions();

    @Mixin
    GlobalOptions global = new GlobalOptions();

    @Override
    public Integer call() {
        try (AppContext ctx = new AppContext(global, search.market)) {
            SearchCriteria criteria = search.toCriteria(ctx.directory());
            if (criteria.origins().isEmpty()) {
                System.err.println("No origin airport resolved from " + search.origins);
                return 2;
            }
            if (criteria.anywhere()) {
                System.err.println("No destinations given - scanning every route from your origins. "
                        + "This is slow; 'airplanner explore' is the quick way to do that.");
            }
            Set<Airline> airlines = search.selectedAirlines();
            if (airlines.stream().noneMatch(Airline::hasFares)) {
                System.err.println("No airline with readable fares selected. Add ryanair to --airlines.");
                return 2;
            }
            SearchResult result = ctx.searchService(search.maxRefine).search(criteria, ctx.progress());
            RouteCoverage coverage = ctx.coverage(airlines);
            ctx.clearProgress();
            Emitter.emit(result, new ResultFormatter(ctx.directory(), coverage), output);
            return result.isEmpty() ? 1 : 0;
        }
    }
}
