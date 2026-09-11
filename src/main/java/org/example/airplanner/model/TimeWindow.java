package org.example.airplanner.model;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * An inclusive time-of-day window such as {@code 07:00-12:00}, saying when a flight
 * is allowed to depart. Named presets ({@code morning}, {@code evening}, ...) are
 * accepted as well.
 */
public record TimeWindow(LocalTime from, LocalTime to) {

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
    public static final TimeWindow ANY = new TimeWindow(LocalTime.MIDNIGHT, LocalTime.of(23, 59));

    public TimeWindow {
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("time window start " + from + " is after its end " + to);
        }
    }

    public static TimeWindow parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ANY;
        }
        String s = raw.trim().toLowerCase(Locale.ROOT);
        switch (s) {
            case "any", "anytime", "all":
                return ANY;
            case "night":
                return new TimeWindow(LocalTime.of(0, 0), LocalTime.of(5, 59));
            case "early":
                return new TimeWindow(LocalTime.of(0, 0), LocalTime.of(8, 59));
            case "morning":
                return new TimeWindow(LocalTime.of(6, 0), LocalTime.of(11, 59));
            case "afternoon":
                return new TimeWindow(LocalTime.of(12, 0), LocalTime.of(17, 59));
            case "evening":
                return new TimeWindow(LocalTime.of(18, 0), LocalTime.of(23, 59));
            case "business":
                return new TimeWindow(LocalTime.of(6, 0), LocalTime.of(9, 30));
            case "afterwork":
                return new TimeWindow(LocalTime.of(17, 0), LocalTime.of(23, 59));
            default:
                break;
        }
        String[] parts = s.split("-");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Bad time window '" + raw + "'. Use HH:mm-HH:mm or one of: "
                    + "night, early, morning, afternoon, evening, business, afterwork, any");
        }
        return new TimeWindow(parseTime(parts[0].trim()), parseTime(parts[1].trim()));
    }

    private static LocalTime parseTime(String t) {
        String v = t.contains(":") ? t : t + ":00";
        if (v.length() == 4) {
            v = "0" + v;
        }
        try {
            return LocalTime.parse(v);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Bad time '" + t + "', expected HH:mm");
        }
    }

    public boolean contains(LocalTime t) {
        return t != null && !t.isBefore(from) && !t.isAfter(to);
    }

    public boolean isAny() {
        return equals(ANY);
    }

    public String apiFrom() {
        return HHMM.format(from);
    }

    public String apiTo() {
        return HHMM.format(to);
    }

    @Override
    public String toString() {
        return isAny() ? "any time" : apiFrom() + "-" + apiTo();
    }
}
