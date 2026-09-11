package org.example.airplanner.cli;

import org.example.airplanner.client.HttpJsonClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "cache", description = "Inspect or clear the local response cache.")
public class CacheCommand implements Callable<Integer> {

    @Option(names = "--clear", description = "Delete every cached response.")
    boolean clear;

    @Override
    public Integer call() {
        Path dir = HttpJsonClient.defaultCacheDir();
        if (clear) {
            try (HttpJsonClient http = HttpJsonClient.withDefaults(true)) {
                System.out.println("Removed " + http.clearCache() + " cached responses from " + dir);
            }
            return 0;
        }
        long files = 0;
        long bytes = 0;
        if (Files.isDirectory(dir)) {
            try (var paths = Files.walk(dir)) {
                var regular = paths.filter(Files::isRegularFile).toList();
                files = regular.size();
                for (Path p : regular) {
                    bytes += Files.size(p);
                }
            } catch (IOException e) {
                System.err.println("Could not read the cache: " + e.getMessage());
                return 1;
            }
        }
        System.out.println("Cache directory: " + dir);
        System.out.printf("%d cached responses, %.1f MB%n", files, bytes / 1024.0 / 1024.0);
        System.out.println("Clear it with: airplanner cache --clear");
        return 0;
    }
}
