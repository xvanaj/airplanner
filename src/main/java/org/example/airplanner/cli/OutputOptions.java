package org.example.airplanner.cli;

import picocli.CommandLine.Option;

import java.nio.file.Path;

/** Where the results go and in what shape. */
public class OutputOptions {

    public enum Format { TABLE, CSV, JSON, HTML }

    @Option(names = "--format", defaultValue = "TABLE",
            description = "Output format: ${COMPLETION-CANDIDATES}. Default: TABLE.")
    public Format format;

    @Option(names = {"-o", "--out"}, paramLabel = "FILE",
            description = "Write the output to this file instead of the console.")
    public Path outFile;

    @Option(names = "--links", description = "Print a ryanair.com booking link for every result.")
    public boolean links;

    @Option(names = "--open", paramLabel = "N",
            description = "Open result number N on ryanair.com in your browser.")
    public Integer open;
}
