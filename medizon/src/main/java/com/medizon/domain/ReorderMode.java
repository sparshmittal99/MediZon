package com.medizon.domain;

public enum ReorderMode {
    OFF("No refill alerts"),
    MANUAL("Alert me, I'll order"),
    AUTO("Order automatically");

    private final String label;

    ReorderMode(String label) { this.label = label; }

    public String getLabel() { return label; }
}
