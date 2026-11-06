package com.medizon.domain;

public enum PaymentStatus {
    PENDING("Pending"), PAID("Paid"), REFUNDED("Refunded");

    private final String label;

    PaymentStatus(String label) { this.label = label; }

    public String getLabel() { return label; }
}
