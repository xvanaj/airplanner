package org.example.airplanner.util;

import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Forgiving parsing for the date, month and duration strings a user types on a command line. */
public final class Dates {

    private static final Pattern RELATIVE = Pattern.compile("^\\+?(\\d+)\\s*([dwmy])$");
    private static final Pattern DURATION = Pattern.compile("^(\\d+)\\s*([mhd])$");

    private Dates() {
    }

    /**
     * Accepts {@code 2026-10-15}, {@code 2026-10} (first of the month), {@code today},
     * {@code tomorrow}, and relative offsets such as {@code +10d}, {@code 3w}, {@code 2m}.
     */
    public static LocalDate parse(String raw, LocalDate fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "today", "now":
                return LocalDate.now();
            case "tomorrow":
                return LocalDate.now().plusDays(1);
            default:
                break;
        }
        Matcher relative = RELATIVE.matcher(s);
        if (relative.matches()) {
            long n = Long.parseLong(relative.group(1));
            return switch (relative.group(2)) {
                case "d" -> LocalDate.now().plusDays(n);
                case "w" -> LocalDate.now().plusWeeks(n);
                case "m" -> LocalDate.now().plusMonths(n);
                default -> LocalDate.now().plusYears(n);
            };
        }
        if (s.matches("\\d{4}-\\d{2}")) {
            return YearMonth.parse(s).atDay(1);
        }
        try {
            return LocalDate.parse(s);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad date '" + raw
                    + "'. Use YYYY-MM-DD, YYYY-MM, today, tomorrow or +30d.");
        }
    }

    /** Accepts {@code 2026-10}, {@code 2026-10-01} or a month name relative to today. */
    public static YearMonth parseMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return YearMonth.now();
        }
        String s = raw.trim();
        if (s.matches("\\d{4}-\\d{2}")) {
            return YearMonth.parse(s);
        }
        return YearMonth.from(parse(s, LocalDate.now()));
    }

    /** Accepts {@code 90m}, {@code 36h}, {@code 2d}. */
    public static Duration parseDuration(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Matcher m = DURATION.matcher(raw.trim().toLowerCase(Locale.ROOT));
        if (!m.matches()) {
            throw new IllegalArgumentException("Bad duration '" + raw + "'. Use 90m, 36h or 2d.");
        }
        long n = Long.parseLong(m.group(1));
        return switch (m.group(2)) {
            case "m" -> Duration.ofMinutes(n);
            case "h" -> Duration.ofHours(n);
            default -> Duration.ofDays(n);
        };
    }

    /** Parses {@code 3} or {@code 2-5} into an inclusive range. */
    public static int[] parseRange(String raw, int[] fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String s = raw.trim();
        try {
            if (s.contains("-")) {
                String[] parts = s.split("-", 2);
                return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            }
            int single = Integer.parseInt(s);
            return new int[]{single, single};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Bad range '" + raw + "'. Use a number like 3 or a range like 2-5.");
        }
    }
}
