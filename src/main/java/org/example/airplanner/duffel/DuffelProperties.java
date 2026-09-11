package org.example.airplanner.duffel;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.nio.file.Path;

@ConfigurationProperties(prefix = "airplanner.duffel")
public class DuffelProperties {
    private boolean enabled;
    private boolean live;
    private String token = "";
    private int dailyLimit = 20;
    private int monthlyLimit = 500;
    private Path usageFile = Path.of(System.getProperty("user.home"), ".airplanner", "duffel-usage.json");

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isLive() { return live; }
    public void setLive(boolean value) { live = value; }
    public String getToken() { return token; }
    public void setToken(String value) { token = value == null ? "" : value.trim(); }
    public int getDailyLimit() { return dailyLimit; }
    public void setDailyLimit(int value) { dailyLimit = value; }
    public int getMonthlyLimit() { return monthlyLimit; }
    public void setMonthlyLimit(int value) { monthlyLimit = value; }
    public Path getUsageFile() { return usageFile; }
    public void setUsageFile(Path value) { usageFile = value; }

    public String unavailableReason() {
        if (!enabled) return "Duffel is disabled. Enable it in the server configuration to search.";
        if (token.isBlank()) return "Duffel needs an access token in the server environment.";
        // Reject unknown formats as well: test mode must never silently send a live token.
        if (!token.startsWith(live ? "duffel_live_" : "duffel_test_")) {
            return "The Duffel token does not match the configured test/live mode.";
        }
        if (dailyLimit < 1 || monthlyLimit < 1) return "Duffel search limits must be positive.";
        return null;
    }
}
