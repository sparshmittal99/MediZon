package com.medizon.domain;

import java.util.List;

public enum ReturnStatus {
    REQUESTED("Awaiting approval", "text-bg-warning"),
    APPROVED("Approved, pickup pending", "text-bg-info"),
    REJECTED("Rejected", "text-bg-danger"),
    PICKED_UP("Picked up", "text-bg-primary"),
    REFUNDED("Refunded", "text-bg-success");

    private final String label;
    private final String badge;

    ReturnStatus(String label, String badge) { this.label = label; this.badge = badge; }

    public String getLabel() { return label; }
    public String getBadge() { return badge; }

    /** Still being worked on: blocks a second return on the same order. */
    public boolean isOpen() { return this == REQUESTED || this == APPROVED || this == PICKED_UP; }

    /** Allowed transitions, used both for validation and the admin buttons. */
    public List<ReturnStatus> getNextStatuses() {
        return switch (this) {
            case REQUESTED -> List.of(APPROVED, REJECTED);
            case APPROVED -> List.of(PICKED_UP);
            case PICKED_UP -> List.of(REFUNDED);
            default -> List.of();
        };
    }

    public boolean canMoveTo(ReturnStatus next) { return getNextStatuses().contains(next); }
}
