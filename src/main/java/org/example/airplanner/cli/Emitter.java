package org.example.airplanner.cli;

import org.example.airplanner.model.Trip;
import org.example.airplanner.output.ResultFormatter;
import org.example.airplanner.search.SearchResult;

import java.awt.Desktop;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Writes a search result out in the requested format, and optionally opens a booking page. */
final class Emitter {

    private Emitter() {
    }

    static void emit(SearchResult result, ResultFormatter formatter, OutputOptions output) {
        String text = switch (output.format) {
            case TABLE -> formatter.table(result, output.links);
            case CSV -> formatter.csv(result);
            case JSON -> formatter.json(result);
            case HTML -> formatter.html(result);
        };
        if (output.outFile != null) {
            try {
                if (output.outFile.getParent() != null) {
                    Files.createDirectories(output.outFile.getParent());
                }
                Files.writeString(output.outFile, text, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not write " + output.outFile, e);
            }
            System.out.println("Wrote " + result.trips().size() + " trips to " + output.outFile.toAbsolutePath());
        } else {
            System.out.print(text);
        }
        openIfRequested(result.trips(), output);
    }

    private static void openIfRequested(List<Trip> trips, OutputOptions output) {
        if (output.open == null) {
            return;
        }
        int index = output.open;
        if (index < 1 || index > trips.size()) {
            System.err.println("Cannot open result " + index + "; there are " + trips.size() + ".");
            return;
        }
        String url = trips.get(index - 1).bookingUrl();
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (IOException | RuntimeException e) {
            // Fall through and just print the link.
        }
        System.out.println(url);
    }
}
