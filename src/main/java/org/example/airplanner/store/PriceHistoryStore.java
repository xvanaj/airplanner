package org.example.airplanner.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Append-only record of the best price a watch has seen, kept in ~/.airplanner/history.json. */
public class PriceHistoryStore {

    /** One observation: the cheapest trip a watch found at a point in time. */
    public record Entry(String watch, Instant at, String route, String outDate, String inDate,
                        double price, String currency, String link) {
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path file;

    public PriceHistoryStore() {
        this(Path.of(System.getProperty("user.home"), ".airplanner", "history.json"));
    }

    public PriceHistoryStore(Path file) {
        this.file = file;
    }

    public Path file() {
        return file;
    }

    public synchronized List<Entry> all() {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
            List<Entry> entries = new ArrayList<>();
            for (JsonNode n : root) {
                entries.add(new Entry(
                        n.path("watch").asText(""),
                        Instant.parse(n.path("at").asText(Instant.EPOCH.toString())),
                        n.path("route").asText(""),
                        n.path("outDate").asText(""),
                        n.path("inDate").asText(""),
                        n.path("price").asDouble(),
                        n.path("currency").asText(""),
                        n.path("link").asText("")));
            }
            return entries;
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public List<Entry> forWatch(String name) {
        return all().stream().filter(e -> e.watch().equals(name)).toList();
    }

    public Optional<Entry> cheapest(String name) {
        return forWatch(name).stream().min(Comparator.comparingDouble(Entry::price));
    }

    public Optional<Entry> latest(String name) {
        return forWatch(name).stream().max(Comparator.comparing(Entry::at));
    }

    public synchronized void append(Entry entry) {
        List<Entry> entries = new ArrayList<>(all());
        entries.add(entry);
        ArrayNode array = mapper.createArrayNode();
        for (Entry e : entries) {
            ObjectNode node = array.addObject();
            node.put("watch", e.watch());
            node.put("at", e.at().toString());
            node.put("route", e.route());
            node.put("outDate", e.outDate());
            node.put("inDate", e.inDate());
            node.put("price", e.price());
            node.put("currency", e.currency());
            node.put("link", e.link());
        }
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(array),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + file, e);
        }
    }
}
