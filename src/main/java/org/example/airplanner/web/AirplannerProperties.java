package org.example.airplanner.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Tunables for the web app, all overridable in application.properties.
 */
@ConfigurationProperties(prefix = "airplanner")
public class AirplannerProperties {

    /**
     * Ryanair market code used for fare requests.
     */
    private String market = "en-gb";

    /**
     * How long a cached fare response stays usable.
     */
    private Duration fareCacheTtl = Duration.ofMinutes(30);

    /**
     * Whether responses are cached on disk at all.
     */
    private boolean cache = true;

    /**
     * Upper bound on the per-day refinement requests one search may make.
     */
    private int maxRefine = 400;

    /**
     * The airports offered as one-click quick picks under "Fly from" - the ones you would
     * actually drive to. Override in application.properties to match where you live.
     */
    private List<String> homeAirports = List.of("BRQ", "VIE", "PRG", "OSR", "BTS", "KRK", "KTW", "WRO");

    /**
     * Enables Stay22 links when set; required by Stay22 and available through free registration.
     */
    private String stay22Aid = "";
    private int stayAdults = AccommodationLinks.DEFAULT_ADULTS;
    private List<Integer> stayChildAges = AccommodationLinks.DEFAULT_CHILD_AGES;

    public int getStayAdults() { return stayAdults; }
    public void setStayAdults(int stayAdults) { this.stayAdults = stayAdults; }
    public List<Integer> getStayChildAges() { return stayChildAges; }
    public void setStayChildAges(List<Integer> stayChildAges) {
        this.stayChildAges = stayChildAges == null ? List.of() : List.copyOf(stayChildAges);
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Duration getFareCacheTtl() {
        return fareCacheTtl;
    }

    public void setFareCacheTtl(Duration fareCacheTtl) {
        this.fareCacheTtl = fareCacheTtl;
    }

    public boolean isCache() {
        return cache;
    }

    public void setCache(boolean cache) {
        this.cache = cache;
    }

    public int getMaxRefine() {
        return maxRefine;
    }

    public void setMaxRefine(int maxRefine) {
        this.maxRefine = maxRefine;
    }

    public List<String> getHomeAirports() {
        return homeAirports;
    }

    public void setHomeAirports(List<String> homeAirports) {
        this.homeAirports = homeAirports == null ? List.of() : List.copyOf(homeAirports);
    }

    public String getStay22Aid() {
        return stay22Aid;
    }

    public void setStay22Aid(String stay22Aid) {
        this.stay22Aid = stay22Aid == null ? "" : stay22Aid;
    }
}
