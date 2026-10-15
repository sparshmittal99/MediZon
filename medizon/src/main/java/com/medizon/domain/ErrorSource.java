package com.medizon.domain;

/** Where a monitored error happened: the Java app (requests, scheduled jobs, logs) or a shopper's browser. */
public enum ErrorSource {
    SERVER("Server"), BROWSER("Browser");

    private final String label;

    ErrorSource(String label) { this.label = label; }

    public String getLabel() { return label; }
}
