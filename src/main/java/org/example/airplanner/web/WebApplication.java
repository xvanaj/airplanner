package org.example.airplanner.web;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.example.airplanner.duffel.DuffelProperties;

/**
 * The web front end: the same search engine as the CLI, behind a page you can fill in.
 *
 * <p>Run it with {@code ./gradlew bootRun} and open http://localhost:8080.
 */
@SpringBootApplication
@EnableConfigurationProperties({AirplannerProperties.class, DuffelProperties.class})
public class WebApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebApplication.class, args);
    }
}
