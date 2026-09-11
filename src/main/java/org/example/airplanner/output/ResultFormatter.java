package org.example.airplanner.output;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.airplanner.model.Airline;
import org.example.airplanner.model.Flight;
import org.example.airplanner.model.Money;
import org.example.airplanner.model.Trip;
import org.example.airplanner.search.AirportDirectory;
import org.example.airplanner.search.RouteCoverage;
import org.example.airplanner.search.SearchResult;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/** Turns a {@link SearchResult} into something a person (or a spreadsheet) can read. */
public class ResultFormatter {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final AirportDirectory directory;
    private final RouteCoverage coverage;

    public ResultFormatter(AirportDirectory directory) {
        this(directory, RouteCoverage.NONE);
    }

    public ResultFormatter(AirportDirectory directory, RouteCoverage coverage) {
        this.directory = directory;
        this.coverage = coverage == null ? RouteCoverage.NONE : coverage;
    }

    private List<Airline> alsoFlying(Trip trip) {
        return coverage.alsoFlying(trip.originCode(), trip.destinationCode());
    }

    private static String codes(List<Airline> airlines) {
        return airlines.stream().map(Airline::code).collect(Collectors.joining("/"));
    }

    private static String names(List<Airline> airlines) {
        return airlines.stream().map(Airline::displayName).collect(Collectors.joining("; "));
    }

    // ---------------------------------------------------------------- console

    public String table(SearchResult result, boolean showLinks) {
        List<Trip> trips = result.trips();
        if (trips.isEmpty()) {
            // Still worth showing the stats and the notes: they usually say why.
            return "No flights matched your filters." + System.lineSeparator()
                    + System.lineSeparator() + summary(result);
        }
        boolean oneWay = result.criteria().oneWay();
        boolean party = result.criteria().passengers() > 1;

        Table table = new Table()
                .column("#", Table.Align.RIGHT)
                .column("ROUTE")
                .column("DESTINATION")
                .column("OUTBOUND");
        if (!oneWay) {
            table.column("RETURN").column("NTS", Table.Align.RIGHT).column("STAY", Table.Align.RIGHT);
        } else {
            table.column("FLIGHT TIME", Table.Align.RIGHT);
        }
        table.column("PRICE", Table.Align.RIGHT);
        if (party) {
            table.column("x" + result.criteria().passengers(), Table.Align.RIGHT);
        }
        boolean anyCoverage = trips.stream().anyMatch(t -> !alsoFlying(t).isEmpty());
        if (anyCoverage) {
            table.column("ALSO");
        }

        int index = 1;
        for (Trip trip : trips) {
            Object[] head = {
                    index++,
                    trip.originCode() + "-" + trip.destinationCode(),
                    directory.describe(trip.destinationCode()),
                    leg(trip.outbound())
            };
            Object[] cells;
            if (oneWay) {
                cells = append(head, trip.outbound().durationText(), money(trip.pricePerAdult()));
            } else {
                cells = append(head, leg(trip.inbound()), trip.nights(), stay(trip.timeAtDestination()),
                        money(trip.pricePerAdult()));
            }
            if (party) {
                cells = append(cells, money(trip.totalForParty()));
            }
            if (anyCoverage) {
                cells = append(cells, codes(alsoFlying(trip)));
            }
            table.row(cells);
        }

        StringBuilder sb = new StringBuilder();
        sb.append(table.render());
        if (anyCoverage) {
            sb.append(System.lineSeparator())
                    .append("ALSO: another airline flies this route. No public fares for it, so check ")
                    .append("its own site - the price above is Ryanair's.")
                    .append(System.lineSeparator());
        }
        sb.append(System.lineSeparator()).append(summary(result));
        if (showLinks) {
            sb.append(System.lineSeparator()).append("Booking links").append(System.lineSeparator());
            int i = 1;
            for (Trip trip : trips) {
                sb.append("  ").append(i).append(". ").append(trip.bookingUrl()).append(System.lineSeparator());
                for (Airline airline : alsoFlying(trip)) {
                    sb.append("     ").append(airline.code()).append(": ").append(trip.searchUrlOn(airline))
                            .append(System.lineSeparator());
                }
                sb.append("     all airlines: ").append(trip.compareUrl()).append(System.lineSeparator());
                i++;
            }
        }
        return sb.toString();
    }

    public String summary(SearchResult result) {
        StringBuilder sb = new StringBuilder();
        Map<String, Trip> best = new LinkedHashMap<>();
        for (Trip trip : result.trips()) {
            best.merge(trip.destinationCode(), trip,
                    (a, b) -> a.pricePerAdult().compareTo(b.pricePerAdult()) <= 0 ? a : b);
        }
        // Only worth a digest when several trips share a destination; in an "anywhere"
        // search the main table is already one row per destination.
        if (best.size() > 1 && result.trips().size() > best.size()) {
            Table digest = new Table().column("CODE").column("DESTINATION")
                    .column("FROM", Table.Align.RIGHT).column("ON");
            best.values().stream()
                    .sorted(Comparator.comparing(Trip::pricePerAdult))
                    .forEach(t -> digest.row(t.destinationCode(), directory.describe(t.destinationCode()),
                            money(t.pricePerAdult()), t.outbound().date().format(DAY)));
            sb.append("Cheapest per destination:").append(System.lineSeparator());
            digest.render().lines().forEach(l -> sb.append("  ").append(l).append(System.lineSeparator()));
            sb.append(System.lineSeparator());
        }
        int tripCount = result.trips().size();
        int routeCount = result.routesScanned();
        sb.append(String.format(Locale.ENGLISH, "%d %s from %d %s in %.1fs (%d requests, %d served from cache).%n",
                tripCount, tripCount == 1 ? "trip" : "trips",
                routeCount, routeCount == 1 ? "route" : "routes",
                result.elapsed().toMillis() / 1000.0, result.networkCalls(), result.cacheHits()));
        if (result.criteria().passengers() > 1) {
            sb.append("Prices are per adult; the party column multiplies by ")
                    .append(result.criteria().passengers())
                    .append(" and ignores seat, bag and card fees.").append(System.lineSeparator());
        }
        List<String> notes = result.notes().stream().distinct().limit(8).toList();
        if (!notes.isEmpty()) {
            sb.append("Notes:").append(System.lineSeparator());
            notes.forEach(n -> sb.append("  - ").append(n).append(System.lineSeparator()));
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- machine readable

    public String csv(SearchResult result) {
        StringBuilder sb = new StringBuilder();
        sb.append("origin,destination,destination_name,out_date,out_day,out_depart,out_arrive,")
                .append("in_date,in_day,in_depart,in_arrive,nights,price_per_adult,total_price,currency,")
                .append("also_flown_by,link")
                .append(System.lineSeparator());
        for (Trip trip : result.trips()) {
            Flight out = trip.outbound();
            Flight back = trip.inbound();
            sb.append(csv(out.origin())).append(',')
                    .append(csv(out.destination())).append(',')
                    .append(csv(directory.describe(out.destination()))).append(',')
                    .append(out.date().format(ISO_DATE)).append(',')
                    .append(out.date().getDayOfWeek()).append(',')
                    .append(out.departure().format(TIME)).append(',')
                    .append(out.arrival() == null ? "" : out.arrival().format(TIME)).append(',')
                    .append(back == null ? "" : back.date().format(ISO_DATE)).append(',')
                    .append(back == null ? "" : back.date().getDayOfWeek().toString()).append(',')
                    .append(back == null ? "" : back.departure().format(TIME)).append(',')
                    .append(back == null || back.arrival() == null ? "" : back.arrival().format(TIME)).append(',')
                    .append(trip.nights()).append(',')
                    .append(trip.pricePerAdult().amount().toPlainString()).append(',')
                    .append(trip.totalForParty().amount().toPlainString()).append(',')
                    .append(trip.pricePerAdult().currency()).append(',')
                    .append(csv(names(alsoFlying(trip)))).append(',')
                    .append(csv(trip.bookingUrl()))
                    .append(System.lineSeparator());
        }
        return sb.toString();
    }

    public String json(SearchResult result) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        ObjectNode query = root.putObject("query");
        query.put("origins", String.join(",", result.criteria().origins()));
        query.put("destinations", String.join(",", result.criteria().destinations()));
        query.put("departFrom", result.criteria().departFrom().toString());
        query.put("departTo", result.criteria().departTo().toString());
        query.put("outboundTime", result.criteria().outboundTime().toString());
        query.put("inboundTime", result.criteria().inboundTime().toString());
        query.put("outboundDays", result.criteria().outboundDays().toString());
        query.put("inboundDays", result.criteria().inboundDays().toString());
        query.put("nights", result.criteria().minNights() + "-" + result.criteria().maxNights());
        query.put("currency", result.criteria().currency());
        query.put("passengers", result.criteria().passengers());

        ArrayNode trips = root.putArray("trips");
        for (Trip trip : result.trips()) {
            ObjectNode t = trips.addObject();
            t.put("origin", trip.originCode());
            t.put("destination", trip.destinationCode());
            t.put("destinationName", directory.describe(trip.destinationCode()));
            t.set("outbound", flightNode(mapper, trip.outbound()));
            if (trip.inbound() != null) {
                t.set("inbound", flightNode(mapper, trip.inbound()));
            }
            t.put("nights", trip.nights());
            t.put("pricePerAdult", trip.pricePerAdult().amount());
            t.put("totalPrice", trip.totalForParty().amount());
            t.put("currency", trip.pricePerAdult().currency());
            t.put("link", trip.bookingUrl());
            t.put("compareLink", trip.compareUrl());
            List<Airline> also = alsoFlying(trip);
            if (!also.isEmpty()) {
                ArrayNode alsoNode = t.putArray("alsoFlownBy");
                for (Airline airline : also) {
                    ObjectNode entry = alsoNode.addObject();
                    entry.put("airline", airline.displayName());
                    entry.put("code", airline.code());
                    entry.put("fares", airline.hasFares());
                    entry.put("link", trip.searchUrlOn(airline));
                }
            }
        }
        ObjectNode stats = root.putObject("stats");
        stats.put("routesScanned", result.routesScanned());
        stats.put("routesSkipped", result.routesSkipped());
        stats.put("requests", result.networkCalls());
        stats.put("cacheHits", result.cacheHits());
        stats.put("elapsedMs", result.elapsed().toMillis());
        ArrayNode notes = root.putArray("notes");
        result.notes().stream().distinct().forEach(notes::add);
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise the result", e);
        }
    }

    private ObjectNode flightNode(ObjectMapper mapper, Flight flight) {
        ObjectNode node = mapper.createObjectNode();
        node.put("date", flight.date().toString());
        node.put("dayOfWeek", flight.date().getDayOfWeek().toString());
        node.put("departure", flight.departure().toString());
        node.put("arrival", flight.arrival() == null ? null : flight.arrival().toString());
        node.put("flightNumber", flight.flightNumber());
        node.put("durationMinutes", flight.duration() == null ? null : flight.duration().toMinutes());
        node.put("price", flight.price().amount());
        return node;
    }

    // ---------------------------------------------------------------- html

    public String html(SearchResult result) {
        StringBuilder rows = new StringBuilder();
        int index = 1;
        for (Trip trip : result.trips()) {
            rows.append("<tr>")
                    .append("<td>").append(index++).append("</td>")
                    .append("<td><strong>").append(esc(trip.originCode())).append(" &rarr; ")
                    .append(esc(trip.destinationCode())).append("</strong><br><span class=\"muted\">")
                    .append(esc(directory.describe(trip.destinationCode()))).append("</span></td>")
                    .append("<td>").append(esc(leg(trip.outbound()))).append("</td>")
                    .append("<td>").append(trip.inbound() == null ? "&mdash;" : esc(leg(trip.inbound())))
                    .append("</td>")
                    .append("<td class=\"num\">").append(trip.inbound() == null ? "&mdash;" : trip.nights())
                    .append("</td>")
                    .append("<td class=\"num price\">").append(esc(money(trip.pricePerAdult()))).append("</td>")
                    .append("<td><a href=\"").append(esc(trip.bookingUrl())).append("\" target=\"_blank\">book</a>");
            for (Airline airline : alsoFlying(trip)) {
                rows.append(" <a href=\"").append(esc(trip.searchUrlOn(airline)))
                        .append("\" target=\"_blank\" title=\"")
                        .append(esc(airline.displayName() + " flies this route too - no public fares, check there"))
                        .append("\">").append(esc(airline.code())).append("</a>");
            }
            rows.append(" <a href=\"").append(esc(trip.compareUrl()))
                    .append("\" target=\"_blank\" title=\"Every airline on these dates\">all</a>");
            rows.append("</td>").append("</tr>");
        }
        String criteria = "Depart " + result.criteria().departFrom() + " to " + result.criteria().departTo()
                + " &middot; out " + result.criteria().outboundDays() + " " + result.criteria().outboundTime()
                + " &middot; back " + result.criteria().inboundDays() + " " + result.criteria().inboundTime()
                + " &middot; " + result.criteria().minNights() + "-" + result.criteria().maxNights() + " nights";
        return """
                <!doctype html>
                <html lang="en">
                <head>
                <meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Ryanair trip options</title>
                <style>
                  :root { color-scheme: light dark; }
                  body { font: 15px/1.5 system-ui, -apple-system, Segoe UI, sans-serif; margin: 2rem auto;
                         max-width: 60rem; padding: 0 1rem; }
                  h1 { font-size: 1.4rem; margin-bottom: .25rem; }
                  .muted { color: #6b7280; font-size: .85em; }
                  table { border-collapse: collapse; width: 100%%; margin-top: 1.25rem; }
                  th, td { text-align: left; padding: .5rem .6rem; border-bottom: 1px solid #d1d5db; }
                  th { font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; color: #6b7280; }
                  td.num { text-align: right; }
                  td.price { font-variant-numeric: tabular-nums; font-weight: 600; }
                  tr:hover td { background: rgba(127,127,127,.08); }
                  a { color: #2563eb; }
                </style>
                </head>
                <body>
                <h1>Ryanair trip options</h1>
                <p class="muted">%s</p>
                <table>
                  <thead><tr><th>#</th><th>Route</th><th>Outbound</th><th>Return</th><th>Nights</th>
                  <th>Price</th><th></th></tr></thead>
                  <tbody>%s</tbody>
                </table>
                <p class="muted">%d trips, generated %s. Prices are per adult and change constantly &mdash;
                always confirm on ryanair.com.</p>
                </body>
                </html>
                """.formatted(criteria, rows, result.trips().size(), java.time.LocalDateTime.now()
                .withNano(0).toString().replace('T', ' '));
    }

    // ---------------------------------------------------------------- small helpers

    private String leg(Flight flight) {
        String arrival = flight.arrival() == null ? "" : "-" + flight.arrival().format(TIME);
        return flight.date().format(DAY) + " " + flight.departure().format(TIME) + arrival;
    }

    private static String stay(Duration duration) {
        if (duration == null) {
            return "-";
        }
        long days = duration.toDays();
        long hours = duration.toHours() % 24;
        return days > 0 ? days + "d" + hours + "h" : duration.toHours() + "h";
    }

    private static String money(Money money) {
        return money.amount().toPlainString() + " " + money.currency();
    }

    private static Object[] append(Object[] base, Object... extra) {
        Object[] result = new Object[base.length + extra.length];
        System.arraycopy(base, 0, result, 0, base.length);
        System.arraycopy(extra, 0, result, base.length, extra.length);
        return result;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"")) {
            return '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static String esc(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
