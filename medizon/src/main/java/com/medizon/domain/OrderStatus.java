package com.medizon.domain;

import java.util.List;

public enum OrderStatus {
    PLACED("Placed"), CONFIRMED("Confirmed"), SHIPPED("Shipped"), DELIVERED("Delivered"), CANCELLED("Cancelled");

    private final String label;

    OrderStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Allowed transitions, used both for validation and the admin dropdown. */
    public List<OrderStatus> getNextStatuses() {
        return switch (this) {
            case PLACED -> List.of(CONFIRMED, CANCELLED);
            case CONFIRMED -> List.of(SHIPPED, CANCELLED);
            case SHIPPED -> List.of(DELIVERED);
            default -> List.of();
        };
    }

    public boolean canMoveTo(OrderStatus next) { return getNextStatuses().contains(next); }

    public String getBadge() {
        return switch (this) {
            case PLACED -> "text-bg-secondary";
            case CONFIRMED -> "text-bg-info";
            case SHIPPED -> "text-bg-primary";
            case DELIVERED -> "text-bg-success";
            case CANCELLED -> "text-bg-danger";
        };
    }
}
