package org.example.airplanner.model;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Which weekdays a flight is allowed to depart on. */
public record DayFilter(Set<DayOfWeek> days) {

    public static final DayFilter ANY = new DayFilter(EnumSet.allOf(DayOfWeek.class));

    public DayFilter {
        days = days.isEmpty() ? EnumSet.allOf(DayOfWeek.class) : EnumSet.copyOf(days);
    }

    public static DayFilter parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ANY;
        }
        EnumSet<DayOfWeek> set = EnumSet.noneOf(DayOfWeek.class);
        for (String token : raw.split("[,;\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            switch (token.trim().toLowerCase(Locale.ROOT)) {
                case "any", "all", "daily" -> set.addAll(EnumSet.allOf(DayOfWeek.class));
                case "weekend", "we" -> set.addAll(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
                case "weekdays", "workdays", "wd" -> set.addAll(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
                default -> set.add(single(token.trim()));
            }
        }
        return new DayFilter(set);
    }

    private static DayOfWeek single(String token) {
        String t = token.toUpperCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            if (d.name().equals(t) || (t.length() >= 2 && d.name().startsWith(t))) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown day '" + token + "'. Use mon..sun, weekend, weekdays or any.");
    }

    public boolean allows(DayOfWeek day) {
        return days.contains(day);
    }

    public boolean isAny() {
        return days.size() == 7;
    }

    /** Comma separated upper-case day names, the format the Ryanair fare finder expects. */
    public String apiValue() {
        return days.stream().sorted().map(Enum::name).collect(Collectors.joining(","));
    }

    @Override
    public String toString() {
        if (isAny()) {
            return "any day";
        }
        return days.stream().sorted()
                .map(d -> d.name().charAt(0) + d.name().substring(1, 3).toLowerCase(Locale.ROOT))
                .collect(Collectors.joining(","));
    }
}
