package com.medizon.geo;

/** A geocoder is down, out of quota or too slow; the caller tries the next one. */
public class GeoUnavailableException extends RuntimeException {
    public GeoUnavailableException(String message) { super(message); }
    public GeoUnavailableException(String message, Throwable cause) { super(message, cause); }
}
