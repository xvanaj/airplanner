package org.example.airplanner.client;

/** Something went wrong talking to Ryanair's public endpoints. */
public class RyanairApiException extends RuntimeException {

    public RyanairApiException(String message) {
        super(message);
    }

    public RyanairApiException(String message, Throwable cause) {
        super(message, cause);
    }
}
