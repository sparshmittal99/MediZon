package com.medizon.domain;

public enum PrescriptionStatus {
    /** Uploaded; the customer checks it (or removes a wrong file) before sending it to the pharmacist. */
    DRAFT("Not sent yet", "text-bg-light border"),
    PENDING("Awaiting review", "text-bg-warning"),
    APPROVED("Approved", "text-bg-success"),
    REJECTED("Rejected", "text-bg-danger"),
    /** Everything prescribed has been ordered: a new prescription is needed for more. */
    USED_UP("Used up", "text-bg-secondary");

    private final String label;
    private final String badge;

    PrescriptionStatus(String label, String badge) { this.label = label; this.badge = badge; }

    public String getLabel() { return label; }
    public String getBadge() { return badge; }
}
