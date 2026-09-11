package org.example.airplanner.cli;

import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.output.ResultFormatter;
import org.example.airplanner.search.SearchResult;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;

import java.util.concurrent.Callable;

@Command(name = "explore",
        description = "Where can I go? Cheapest destination from each origin, using Ryanair's own "
                + "'anywhere' search. One request per origin, so it is fast.",
        sortOptions = false,
        footer = {
                "",
                "Examples:",
                "  airplanner explore -f VIE,BTS --month 2026-11 --out-days fri --back-days sun --budget 80",
                "  airplanner explore -f DUB --one-way --out-time morning -n 40"
        })
public class ExploreCommand implements Callable<Integer> {

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
            SearchResult result = ctx.searchService(search.maxRefine).exploreResult(criteria, ctx.progress());
            var coverage = ctx.coverage(search.selectedAirlines());
            ctx.clearProgress();
            Emitter.emit(result, new ResultFormatter(ctx.directory(), coverage), output);
            return result.isEmpty() ? 1 : 0;
        }
    }
}
