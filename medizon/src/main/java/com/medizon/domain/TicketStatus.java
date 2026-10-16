package com.medizon.domain;

/**
 * Where a support ticket is: with support, passed to a specialist team, handed back to support once the team has
 * solved it, or closed. A customer can reopen a closed ticket for a few days, which puts it back with support.
 */
public enum TicketStatus {
    OPEN("Open", "Open", "text-bg-primary"),
    WITH_TEAM("With team", "With a specialist team", "text-bg-warning"),
    RESOLVED("Solved by team", "Resolved, closing soon", "text-bg-info"),
    CLOSED("Closed", "Closed", "text-bg-secondary");

    private final String label;
    private final String customerLabel;
    private final String badge;

    TicketStatus(String label, String customerLabel, String badge) {
        this.label = label;
        this.customerLabel = customerLabel;
        this.badge = badge;
    }

    /** What staff see. */
    public String getLabel() { return label; }

    /** What the customer sees: no internal hand-offs. */
    public String getCustomerLabel() { return customerLabel; }

    public String getBadge() { return badge; }

    public boolean isClosed() { return this == CLOSED; }
}
