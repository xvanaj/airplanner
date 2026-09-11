package org.example.airplanner.web;

import org.example.airplanner.duffel.OfferSearch;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public class DuffelForm {
    private String origin = "PRG";
    private String destination = "LHR";
    private String departureDate = LocalDate.now().plusDays(30).toString();
    private String returnDate = "";
    private int adults = 1;
    private int maxConnections = 0;

    public OfferSearch toSearch() {
        try {
            return new OfferSearch(origin, destination, LocalDate.parse(departureDate),
                    returnDate == null || returnDate.isBlank() ? null : LocalDate.parse(returnDate), adults, maxConnections);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new IllegalArgumentException("Enter dates in YYYY-MM-DD format.");
        }
    }
    public String getOrigin() { return origin; }
    public void setOrigin(String v) { origin = v; }
    public String getDestination() { return destination; }
    public void setDestination(String v) { destination = v; }
    public String getDepartureDate() { return departureDate; }
    public void setDepartureDate(String v) { departureDate = v; }
    public String getReturnDate() { return returnDate; }
    public void setReturnDate(String v) { returnDate = v; }
    public int getAdults() { return adults; }
    public void setAdults(int v) { adults = v; }
    public int getMaxConnections() { return maxConnections; }
    public void setMaxConnections(int v) { maxConnections = v; }
}
