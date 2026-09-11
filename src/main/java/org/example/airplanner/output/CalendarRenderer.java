package org.example.airplanner.output;

import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.model.DayFilter;
import org.example.airplanner.model.TimeWindow;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A month-at-a-glance price grid for one route. */
public class CalendarRenderer {

    private static final int CELL = 8;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final TimeWindow window;
    private final DayFilter days;

    public CalendarRenderer(TimeWindow window, DayFilter days) {
        this.window = window == null ? TimeWindow.ANY : window;
        this.days = days == null ? DayFilter.ANY : days;
    }

    public String render(String origin, String destination, YearMonth month,
                         List<RyanairClient.DayFare> fares, String currency) {
        Map<LocalDate, RyanairClient.DayFare> byDay = new LinkedHashMap<>();
        fares.forEach(f -> byDay.put(f.day(), f));

        StringBuilder sb = new StringBuilder();
        sb.append(origin).append(" -> ").append(destination).append("   ")
                .append(month.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH))
                .append(' ').append(month.getYear())
                .append("   (cheapest fare per day, ").append(currency).append(')')
                .append(System.lineSeparator());
        for (DayOfWeek d : DayOfWeek.values()) {
            sb.append(pad(d.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH)));
        }
        sb.append(System.lineSeparator());

        LocalDate first = month.atDay(1);
        int leading = first.getDayOfWeek().getValue() - 1;
        StringBuilder dayLine = new StringBuilder();
        StringBuilder priceLine = new StringBuilder();
        StringBuilder timeLine = new StringBuilder();
        dayLine.append(" ".repeat(leading * CELL));
        priceLine.append(" ".repeat(leading * CELL));
        timeLine.append(" ".repeat(leading * CELL));

        for (int dayOfMonth = 1; dayOfMonth <= month.lengthOfMonth(); dayOfMonth++) {
            LocalDate date = month.atDay(dayOfMonth);
            RyanairClient.DayFare fare = byDay.get(date);
            boolean dayAllowed = days.allows(date.getDayOfWeek());
            boolean past = date.isBefore(LocalDate.now());
            String price;
            String time;
            if (!dayAllowed || past) {
                price = ".";
                time = "";
            } else if (fare == null || !fare.available() || fare.price() == null) {
                price = "-";
                time = "";
            } else {
                boolean inWindow = fare.departure() != null && window.contains(fare.departure().toLocalTime());
                price = (inWindow ? "" : "(") + format(fare.price()) + (inWindow ? "" : ")");
                time = fare.departure() == null ? "" : fare.departure().format(TIME);
            }
            dayLine.append(pad(String.valueOf(dayOfMonth)));
            priceLine.append(pad(price));
            timeLine.append(pad(time));

            if (date.getDayOfWeek() == DayOfWeek.SUNDAY || dayOfMonth == month.lengthOfMonth()) {
                sb.append(dayLine.toString().stripTrailing()).append(System.lineSeparator());
                sb.append(priceLine.toString().stripTrailing()).append(System.lineSeparator());
                sb.append(timeLine.toString().stripTrailing()).append(System.lineSeparator());
                sb.append(System.lineSeparator());
                dayLine.setLength(0);
                priceLine.setLength(0);
                timeLine.setLength(0);
            }
        }
        sb.append("  '-' no flight or sold out, '.' excluded by your day filter");
        if (!window.isAny()) {
            sb.append(", '(price)' departs outside ").append(window);
        }
        sb.append('.').append(System.lineSeparator());
        return sb.toString();
    }

    private static String format(double price) {
        return price >= 100 ? String.valueOf(Math.round(price)) : String.format(Locale.ENGLISH, "%.0f", price);
    }

    private static String pad(String value) {
        String v = value.length() > CELL - 1 ? value.substring(0, CELL - 1) : value;
        return v + " ".repeat(CELL - v.length());
    }
}
