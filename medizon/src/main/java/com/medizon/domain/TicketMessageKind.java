package com.medizon.domain;

/** Who a ticket message is from, and who sees it. Internal notes are between staff only. */
public enum TicketMessageKind {
    CUSTOMER, STAFF, SYSTEM, NOTE;

    public boolean isVisibleToCustomer() { return this != NOTE; }
}
