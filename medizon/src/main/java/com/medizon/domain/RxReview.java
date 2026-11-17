package com.medizon.domain;

/**
 * Where the prescription of an order placed with {@link RxCheck} stands. While it's pending only the admin can confirm
 * the order; rejected, the order is cancelled.
 */
public enum RxReview {
    PENDING("Prescription pending", "text-bg-warning"),
    APPROVED("Prescription approved", "text-bg-success"),
    REJECTED("Prescription rejected", "text-bg-danger");

    private final String label;
    private final String badge;

    RxReview(String label, String badge) { this.label = label; this.badge = badge; }

    public String getLabel() { return label; }

    public String getBadge() { return badge; }
}
