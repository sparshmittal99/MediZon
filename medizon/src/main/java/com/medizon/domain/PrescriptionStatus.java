package com.medizon.domain;

public enum PrescriptionStatus {
    PENDING("Awaiting review", "text-bg-warning"),
    APPROVED("Approved", "text-bg-success"),
    REJECTED("Rejected", "text-bg-danger");

    private final String label;
    private final String badge;

    PrescriptionStatus(String label, String badge) { this.label = label; this.badge = badge; }

    public String getLabel() { return label; }
    public String getBadge() { return badge; }
}
