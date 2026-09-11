package org.example.airplanner;

import org.example.airplanner.cli.AirportsCommand;
import org.example.airplanner.cli.CacheCommand;
import org.example.airplanner.cli.CalendarCommand;
import org.example.airplanner.cli.ExploreCommand;
import org.example.airplanner.cli.SearchCommand;
import org.example.airplanner.cli.WatchCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point. Everything the tool does is a subcommand of this one; running it with
 * no subcommand prints the usage.
 */
@Command(name = "airplanner",
        mixinStandardHelpOptions = true,
        version = "airplanner 1.0",
        subcommands = {
                SearchCommand.class,
                ExploreCommand.class,
                CalendarCommand.class,
                AirportsCommand.class,
                WatchCommand.class,
                CacheCommand.class,
                CommandLine.HelpCommand.class
        },
        description = "Find Ryanair trips from several home airports to several destinations, "
                + "filtered by day of week and time of day.",
        footer = {
                "",
                "Prices come from Ryanair's own public endpoints, are per adult and exclude seats, bags",
                "and card fees. Always confirm on ryanair.com before booking."
        })
public class AirPlanner implements Runnable {

    @Override
    public void run() {
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new AirPlanner())
                .setCaseInsensitiveEnumValuesAllowed(true)
                .setExecutionExceptionHandler((ex, commandLine, parseResult) -> {
                    commandLine.getErr().println("Error: " + ex.getMessage());
                    if (System.getenv("AIRPLANNER_DEBUG") != null) {
                        ex.printStackTrace(commandLine.getErr());
                    }
                    return 2;
                })
                .execute(args);
        System.exit(exitCode);
    }
}
