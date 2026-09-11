package org.example.airplanner.cli;

import picocli.CommandLine.Option;

/** Options every command understands: caching and chattiness. */
public class GlobalOptions {

    @Option(names = "--no-cache", description = "Ignore the local response cache and always ask Ryanair.")
    public boolean noCache;

    @Option(names = "--cache-ttl", paramLabel = "MINUTES", defaultValue = "30",
            description = "How long cached fares stay valid. Default: 30 minutes.")
    public int cacheTtlMinutes;

    @Option(names = {"-q", "--quiet"}, description = "Do not print progress while searching.")
    public boolean quiet;
}
