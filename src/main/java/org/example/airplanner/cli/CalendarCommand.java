package org.example.airplanner.cli;

import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.TimeWindow;
import org.example.airplanner.output.CalendarRenderer;
import org.example.airplanner.util.Dates;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "calendar",
        description = "Month-by-month price grid for a route, so you can see which dates are cheap.",
        sortOptions = false,
        footer = {
                "",
                "Example:",
                "  airplanner calendar -f VIE -t BCN --month 2026-10 --months 3 --out-time evening"
        })
public class CalendarCommand implements Callable<Integer> {

    @Option(names = {"-f", "--from"}, split = ",", required = true, paramLabel = "AIRPORT",
            description = "Origin airports.")
    List<String> origins = new ArrayList<>();

    @Option(names = {"-t", "--to"}, split = ",", required = true, paramLabel = "AIRPORT",
            description = "Destination airports.")
    List<String> destinations = new ArrayList<>();

    @Option(names = "--month", paramLabel = "YYYY-MM", description = "First month to show. Default: this month.")
    String month;

    @Option(names = "--months", defaultValue = "1", description = "How many months to show. Default: 1.")
    int months;

    @Option(names = "--out-time", defaultValue = "any",
            description = "Highlight only departures inside this window; others are shown in brackets.")
    String outTime;

    @Option(names = "--out-days", defaultValue = "any", description = "Weekdays to consider; others show as '.'.")
    String outDays;

    @Option(names = {"-c", "--currency"}, defaultValue = "EUR", description = "Currency code. Default: EUR.")
    String currency;

    @Option(names = "--return-leg", description = "Show the destination-to-origin direction as well.")
    boolean returnLeg;

    @Mixin
    GlobalOptions global = new GlobalOptions();

    @Override
    public Integer call() {
        try (AppContext ctx = new AppContext(global, "en-gb")) {
            List<String> from = ctx.directory().resolveCodes(origins);
            List<String> to = ctx.directory().resolveCodes(destinations);
            CalendarRenderer renderer = new CalendarRenderer(TimeWindow.parse(outTime), DayFilter.parse(outDays));
            YearMonth start = Dates.parseMonth(month);
            StringBuilder sb = new StringBuilder();
            for (String origin : from) {
                for (String destination : to) {
                    if (origin.equals(destination)) {
                        continue;
                    }
                    for (int i = 0; i < Math.max(1, months); i++) {
                        YearMonth ym = start.plusMonths(i);
                        ctx.progress("loading " + origin + "-" + destination + " " + ym);
                        sb.append(renderer.render(origin, destination, ym,
                                ctx.client().cheapestPerDay(origin, destination, ym, currency), currency));
                        sb.append(System.lineSeparator());
                        if (returnLeg) {
                            ctx.progress("loading " + destination + "-" + origin + " " + ym);
                            sb.append(renderer.render(destination, origin, ym,
                                    ctx.client().cheapestPerDay(destination, origin, ym, currency), currency));
                            sb.append(System.lineSeparator());
                        }
                    }
                }
            }
            ctx.clearProgress();
            System.out.print(sb);
            return 0;
        }
    }
}
