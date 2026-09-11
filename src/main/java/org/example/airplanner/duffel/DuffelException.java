package org.example.airplanner.duffel;

/** Only safe, locally generated messages reach the browser; upstream bodies can contain secrets. */
public class DuffelException extends RuntimeException {
    private final int status;
    public DuffelException(int status, String message) { super(message); this.status = status; }
    public int status() { return status; }
}
