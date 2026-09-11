package org.example.airplanner.cli;

import org.example.airplanner.model.SearchCriteria;
import org.example.airplanner.model.Trip;
import org.example.airplanner.output.Table;
import org.example.airplanner.search.SearchResult;
import org.example.airplanner.store.PriceHistoryStore;
import org.example.airplanner.util.Dates;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;

@Command(name = "watch",
        description = "Re-run a search on a timer, remember the cheapest price seen and shout when it drops.",
        sortOptions = false,
        footer = {
                "",
                "Examples:",
                "  airplanner watch --name xmas -f VIE,BTS -t AGP --month 2026-12 --every 2h --alert-below 90",
                "  airplanner watch --name xmas --history"
        })
public class WatchCommand implements Callable<Integer> {

    @Option(names = "--name", defaultValue = "default",
            description = "Name this watch so its price history is kept separately. Default: default.")
    String name;

    @Option(names = "--every", defaultValue = "1h", paramLabel = "DURATION",
            description = "How often to re-check: 30m, 2h, 1d. Default: 1h.")
    String every;

    @Option(names = "--times", defaultValue = "0",
            description = "Stop after this many checks. 0 means run until you stop it. Default: 0.")
    int times;

    @Option(names = "--alert-below", paramLabel = "AMOUNT",
            description = "Print an alert whenever the cheapest trip is at or below this price.")
    Double alertBelow;

    @Option(names = "--history", description = "Print what this watch has recorded so far and exit.")
    boolean history;

    @Mixin
    SearchOptions search = new SearchOptions();

    @Mixin
    GlobalOptions global = new GlobalOptions();

    private final PriceHistoryStore store = new PriceHistoryStore();

    @Override
    public Integer call() throws Exception {
        if (history) {
            return printHistory();
        }
        Duration interval = Dates.parseDuration(every);
        if (interval == null || interval.toMinutes() < 1) {
            throw new IllegalArgumentException("--every must be at least 1m");
        }
        // A watch wants live prices, so cached fares are of no use to it.
        global.cacheTtlMinutes = 0;

        int checks = 0;
        while (times <= 0 || checks < times) {
            checks++;
            runOnce(checks);
            if (times > 0 && checks >= times) {
                break;
            }
            System.out.println("  next check in " + every + " (Ctrl-C to stop)");
            Thread.sleep(interval.toMillis());
        }
        return 0;
    }

    private void runOnce(int check) {
        try (AppContext ctx = new AppContext(global, search.market)) {
            SearchCriteria criteria = search.toCriteria(ctx.directory());
            SearchResult result = ctx.searchService(search.maxRefine).search(criteria, ctx.progress());
            ctx.clearProgress();
            String stamp = LocalTime.now().withNano(0).toString();
            if (result.isEmpty()) {
                System.out.println("[" + stamp + "] check " + check + ": nothing matched.");
                return;
            }
            Trip best = result.trips().stream().min(Comparator.comparing(Trip::pricePerAdult)).orElseThrow();
            double price = best.pricePerAdult().doubleValue();
            var previousBest = store.cheapest(name);
            store.append(new PriceHistoryStore.Entry(name, Instant.now(),
                    best.originCode() + "-" + best.destinationCode(),
                    best.outbound().date().toString(),
                    best.inbound() == null ? "" : best.inbound().date().toString(),
                    price, best.pricePerAdult().currency(), best.bookingUrl()));

            StringBuilder line = new StringBuilder();
            line.append('[').append(stamp).append("] check ").append(check).append(": ")
                    .append(best.originCode()).append("-").append(best.destinationCode()).append(' ')
                    .append(best.outbound().date());
            if (best.inbound() != null) {
                line.append(" .. ").append(best.inbound().date());
            }
            line.append("  ").append(best.pricePerAdult());
            previousBest.ifPresent(p -> {
                double delta = price - p.price();
                if (Math.abs(delta) >= 0.01) {
                    line.append(delta < 0 ? "  DROP of " : "  up by ")
                            .append(String.format("%.2f", Math.abs(delta)))
                            .append(" vs best seen (").append(String.format("%.2f", p.price())).append(')');
                } else {
                    line.append("  (matches the best seen)");
                }
            });
            System.out.println(line);
            boolean cheaperThanEver = previousBest.isEmpty() || price < previousBest.get().price() - 0.005;
            boolean underThreshold = alertBelow != null && price <= alertBelow;
            if (cheaperThanEver || underThreshold) {
                System.out.print((char) 7);
                System.out.println("  ALERT: " + best.pricePerAdult() + " -> " + best.bookingUrl());
            }
        }
    }

    private int printHistory() {
        List<PriceHistoryStore.Entry> entries = store.forWatch(name);
        if (entries.isEmpty()) {
            System.out.println("No history yet for watch '" + name + "' (" + store.file() + ").");
            return 1;
        }
        Table table = new Table().column("WHEN").column("ROUTE").column("OUT").column("BACK")
                .column("PRICE", Table.Align.RIGHT);
        entries.forEach(e -> table.row(e.at().toString().replace('T', ' ').substring(0, 16),
                e.route(), e.outDate(), e.inDate(),
                String.format("%.2f %s", e.price(), e.currency())));
        System.out.print(table.render());
        store.cheapest(name).ifPresent(e -> System.out.println(System.lineSeparator()
                + "Cheapest seen: " + String.format("%.2f %s", e.price(), e.currency())
                + " on " + e.outDate() + "  " + e.link()));
        return 0;
    }
}
