package org.example.airplanner.web;

import org.example.airplanner.model.Trip;
import org.example.airplanner.search.AirportDirectory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** External accommodation searches; no provider API calls or price estimates. */
public record AccommodationLinks(String destination, String dates, String guests, List<Link> links) {

    static final int DEFAULT_ADULTS = 2;
    static final List<Integer> DEFAULT_CHILD_AGES = List.of(2, 5, 7);

    public record Link(String name, String url) {
        public String key() { return name.toLowerCase(java.util.Locale.ROOT).replace(".", ""); }
        public String icon() { return name.equals("Airbnb") ? "⌂" : name.substring(0, 1); }
    }

    static AccommodationLinks forTrip(Trip trip, AirportDirectory directory, String stay22Aid) {
        return forTrip(trip, directory, stay22Aid, DEFAULT_ADULTS, DEFAULT_CHILD_AGES);
    }

    static AccommodationLinks forTrip(Trip trip, AirportDirectory directory, String stay22Aid,
                                       int adults, List<Integer> childAges) {
        if (adults < 1 || childAges == null || childAges.stream().anyMatch(age -> age == null || age < 0 || age > 17)) {
            throw new IllegalArgumentException("Accommodation needs at least one adult and child ages from 0 to 17.");
        }
        String ages = childAges.stream().map(String::valueOf).collect(Collectors.joining(","));
        String guests = adults + " adults · " + childAges.size() + " children"
                + (childAges.isEmpty() ? "" : " (ages " + ages.replace(",", ", ") + ")");
        long infants = childAges.stream().filter(age -> age < 2).count();
        String rentalChildren = "&children=" + (childAges.size() - infants) + "&infants=" + infants;
        String bookingAges = childAges.stream().map(age -> "&age=" + age).collect(Collectors.joining());
        String roomChildren = childAges.stream().map(age -> "1_" + age).collect(Collectors.joining(","));
        String destination = directory.byCode(trip.destinationCode()).map(airport -> {
            String city = airport.cityName() == null || airport.cityName().isBlank()
                    ? airport.name() : airport.cityName();
            return airport.countryName() == null || airport.countryName().isBlank()
                    ? city : city + ", " + airport.countryName();
        }).orElse(trip.destinationCode());

        // Flight timestamps are local to their airports. An overnight arrival starts the stay next day.
        LocalDate checkIn = trip.outbound().arrival() == null ? null : trip.outbound().arrival().toLocalDate();
        LocalDate checkOut = trip.inbound() == null ? null : trip.inbound().date();
        boolean dated = checkIn != null && checkOut != null && checkOut.isAfter(checkIn);
        String dates = dated ? checkIn + " to " + checkOut : "Choose stay dates on the provider's site";
        String location = encode(destination);
        List<Link> links = new ArrayList<>();
        links.add(new Link("Airbnb", "https://www.airbnb.com/s/homes?query=" + location
                + "&adults=" + adults + rentalChildren
                + (dated ? "&checkin=" + checkIn + "&checkout=" + checkOut : "")));
        links.add(new Link("Booking.com", "https://www.booking.com/searchresults.html?ss=" + location
                + "&group_adults=" + adults + "&group_children=" + childAges.size() + bookingAges + "&no_rooms=1"
                + (dated ? "&checkin=" + checkIn + "&checkout=" + checkOut : "")));
        String expediaSearch = "?destination=" + location + "&adults=" + adults
                + (dated ? "&startDate=" + checkIn + "&endDate=" + checkOut : "");
        String hotelChildren = childAges.isEmpty() ? "" : "&children=" + encode(roomChildren);
        links.add(new Link("Expedia", "https://www.expedia.com/Hotel-Search" + expediaSearch + hotelChildren));
        links.add(new Link("Hotels.com", "https://www.hotels.com/Hotel-Search" + expediaSearch + hotelChildren));
        links.add(new Link("Vrbo", "https://www.vrbo.com/search" + expediaSearch + rentalChildren));
        links.add(new Link("Agoda", "https://www.agoda.com/search?textToSearch=" + location
                + "&adults=" + adults + "&rooms=1&children=" + childAges.size()
                + (childAges.isEmpty() ? "" : "&childAges=" + encode(ages))
                + (dated ? "&checkIn=" + checkIn + "&checkOut=" + checkOut : "")));
        if (stay22Aid != null && !stay22Aid.isBlank()) {
            links.add(new Link("Stay22", "https://www.stay22.com/allez/roam?aid=" + encode(stay22Aid.trim())
                    + "&address=" + location + "&adults=" + adults
                    + "&children=" + childAges.size() + (childAges.isEmpty() ? "" : "&childrenAges=" + encode(ages))
                    + (dated ? "&checkin=" + checkIn + "&checkout=" + checkOut : "")));
        }
        return new AccommodationLinks(destination, dates, guests, List.copyOf(links));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
