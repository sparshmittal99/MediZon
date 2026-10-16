package com.medizon.domain;

/** How bad a monitored error is, most severe first. Admins are alerted about errors at or above a set severity. */
public enum ErrorSeverity {
    CRITICAL("Critical", "text-bg-danger"),
    HIGH("High", "text-bg-warning"),
    MEDIUM("Medium", "text-bg-info"),
    LOW("Low", "text-bg-light border");

    private final String label;
    private final String badge;

    ErrorSeverity(String label, String badge) {
        this.label = label;
        this.badge = badge;
    }

    public String getLabel() { return label; }

    /** Bootstrap badge classes for the error panel. */
    public String getBadge() { return badge; }

    public boolean atLeast(ErrorSeverity other) { return ordinal() <= other.ordinal(); }
}
