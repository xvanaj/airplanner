package org.example.airplanner.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Small JSON-over-HTTP client with the manners a public airline API deserves:
 * an on-disk response cache, a request rate limit, a concurrency cap and
 * retries with exponential backoff.
 */
public class HttpJsonClient implements AutoCloseable {

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/126.0.0.0 Safari/537.36";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http;
    private final Path cacheDir;
    private final boolean cacheEnabled;
    private final Semaphore concurrency;
    private final long minRequestGapMillis;
    private final int maxAttempts;
    private final AtomicInteger networkCalls = new AtomicInteger();
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final Object throttleLock = new Object();
    private long lastRequestAt;

    public HttpJsonClient(Path cacheDir, boolean cacheEnabled, int maxConcurrentRequests,
                          long minRequestGapMillis, int maxAttempts) {
        this.cacheDir = cacheDir;
        this.cacheEnabled = cacheEnabled;
        this.concurrency = new Semaphore(Math.max(1, maxConcurrentRequests));
        this.minRequestGapMillis = minRequestGapMillis;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public static HttpJsonClient withDefaults(boolean cacheEnabled) {
        return new HttpJsonClient(defaultCacheDir(), cacheEnabled, 6, 120, 3);
    }

    public static Path defaultCacheDir() {
        return Path.of(System.getProperty("user.home"), ".airplanner", "cache");
    }

    /** GET the URL and parse it as JSON, serving a cached copy younger than {@code ttl} if there is one. */
    public JsonNode get(String url, Duration ttl) {
        String body = getText(url, ttl);
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new RyanairApiException("The response was not valid JSON: " + url, e);
        }
    }

    /** As {@link #get}, but hands back the raw body - some things we read are not JSON. */
    public String getText(String url, Duration ttl) {
        Path cacheFile = cacheFileFor(url);
        if (cacheEnabled && cacheFile != null && isFresh(cacheFile, ttl)) {
            try {
                cacheHits.incrementAndGet();
                return Files.readString(cacheFile, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // A corrupt cache entry is not worth failing over; fall through to the network.
            }
        }
        String body = fetch(url);
        if (cacheEnabled && cacheFile != null) {
            try {
                Files.createDirectories(cacheFile.getParent());
                Files.writeString(cacheFile, body, StandardCharsets.UTF_8);
            } catch (IOException e) {
                // Caching is best effort.
            }
        }
        return body;
    }

    private String fetch(String url) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                concurrency.acquire();
                try {
                    throttle();
                    networkCalls.incrementAndGet();
                    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(30))
                            .header("User-Agent", USER_AGENT)
                            .header("Accept", "application/json, text/plain, */*")
                            .header("Accept-Language", "en-GB,en;q=0.9")
                            .header("Referer", "https://www.ryanair.com/")
                            .GET()
                            .build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();
                    if (status == 200) {
                        return response.body();
                    }
                    if (status == 404) {
                        // No such route or month: an empty payload is the honest answer.
                        return "{}";
                    }
                    if (status == 429 || status >= 500) {
                        last = new RyanairApiException("Ryanair replied HTTP " + status + " for " + url);
                    } else {
                        throw new RyanairApiException("Ryanair replied HTTP " + status + " for " + url);
                    }
                } finally {
                    concurrency.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RyanairApiException("Interrupted while calling " + url, e);
            } catch (IOException e) {
                last = new RyanairApiException("Could not reach Ryanair: " + e.getMessage(), e);
            }
            sleep(400L * (1L << (attempt - 1)));
        }
        throw last != null ? last : new RyanairApiException("Request failed: " + url);
    }

    private void throttle() {
        if (minRequestGapMillis <= 0) {
            return;
        }
        long waitFor;
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long earliest = lastRequestAt + minRequestGapMillis;
            waitFor = Math.max(0, earliest - now);
            lastRequestAt = Math.max(now, earliest);
        }
        sleep(waitFor);
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean isFresh(Path file, Duration ttl) {
        try {
            if (!Files.exists(file)) {
                return false;
            }
            Instant modified = Files.getLastModifiedTime(file).toInstant();
            return modified.plus(ttl).isAfter(Instant.now());
        } catch (IOException e) {
            return false;
        }
    }

    private Path cacheFileFor(String url) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String hex = HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
            return cacheDir.resolve(hex.substring(0, 2)).resolve(hex + ".json");
        } catch (Exception e) {
            return null;
        }
    }

    public int networkCalls() {
        return networkCalls.get();
    }

    public int cacheHits() {
        return cacheHits.get();
    }

    /** Deletes every cached response. Returns the number of files removed. */
    public int clearCache() {
        if (!Files.isDirectory(cacheDir)) {
            return 0;
        }
        int[] removed = {0};
        try (var paths = Files.walk(cacheDir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    if (Files.isRegularFile(p) && Files.deleteIfExists(p)) {
                        removed[0]++;
                    }
                } catch (IOException ignored) {
                    // Leave whatever we cannot delete.
                }
            });
        } catch (IOException e) {
            throw new RyanairApiException("Could not clear the cache at " + cacheDir, e);
        }
        return removed[0];
    }

    @Override
    public void close() {
        http.close();
    }
}
