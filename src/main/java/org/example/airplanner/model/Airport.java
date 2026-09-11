package org.example.airplanner.model;

import java.time.ZoneId;
import java.time.ZoneOffset;

/** An airport served by Ryanair, as published by the airline's public airport directory. */
public record Airport(String code,
                      String name,
                      String cityName,
                      String regionName,
                      String countryName,
                      String countryCode,
                      double latitude,
                      double longitude,
                      String timeZone,
                      boolean base) {

    public ZoneId zone() {
        if (timeZone == null || timeZone.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(timeZone);
        } catch (RuntimeException e) {
            return ZoneOffset.UTC;
        }
    }

    /** "DUB Dublin (Ireland)" */
    public String label() {
        return code + " " + name + " (" + countryName + ")";
    }

    /** Great-circle distance in km to another airport. */
    public double distanceKmTo(Airport other) {
        double earthRadiusKm = 6371.0;
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(latitude)) * Math.cos(Math.toRadians(other.latitude))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
