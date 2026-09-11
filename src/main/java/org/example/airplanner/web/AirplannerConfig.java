package org.example.airplanner.web;

import org.example.airplanner.client.HttpJsonClient;
import org.example.airplanner.client.RyanairClient;
import org.example.airplanner.client.WizzAirClient;
import org.example.airplanner.duffel.DuffelClient;
import org.example.airplanner.duffel.DuffelProperties;
import org.example.airplanner.duffel.SearchAllowance;
import org.example.airplanner.search.WizzAirRoutes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

/** Wires the shared HTTP client and Ryanair client for the web app. */
@Configuration
public class AirplannerConfig {

    @Bean(destroyMethod = "close")
    public DuffelClient duffelClient(DuffelProperties properties) {
        var allowance = new SearchAllowance(properties.getUsageFile(),
                properties.getDailyLimit(), properties.getMonthlyLimit(), Clock.systemUTC());
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        return new DuffelClient(properties, allowance, http);
    }

    @Bean(destroyMethod = "close")
    public HttpJsonClient httpJsonClient(AirplannerProperties properties) {
        return HttpJsonClient.withDefaults(properties.isCache());
    }

    @Bean
    public RyanairClient ryanairClient(HttpJsonClient http, AirplannerProperties properties) {
        return new RyanairClient(http, properties.getMarket(), properties.getFareCacheTtl());
    }

    @Bean
    public WizzAirClient wizzAirClient(HttpJsonClient http) {
        return new WizzAirClient(http);
    }

    /** Holds Wizz Air's route network for the life of the app; it is one request. */
    @Bean
    public WizzAirRoutes wizzAirRoutes(WizzAirClient client) {
        return new WizzAirRoutes(client);
    }
}
