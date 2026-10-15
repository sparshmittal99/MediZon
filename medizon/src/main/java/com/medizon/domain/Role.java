package com.medizon.domain;

import java.util.List;

/**
 * Who an account belongs to. There is one admin, whose sign-in comes from MEDIZON_ADMIN_EMAIL / MEDIZON_ADMIN_PASSWORD
 * and who can do everything; the admin creates every other staff account with one of the staff roles, each limited to
 * its own part of the back office. Customers sign up themselves (or the admin creates them).
 */
public enum Role {
    CUSTOMER("Customer", "Shops, orders and keeps track of medicines"),
    ADMIN("Admin", "Everything, across all stores"),
    PHARMACIST("Pharmacist", "Reads, approves and manages prescriptions"),
    SUPPORT("Support", "Reads feedback and reviews, looks up orders to help customers"),
    FINANCE("Finance", "Sales, GST and accounting reports"),
    STORE("Store", "Runs one store: its catalogue, orders, deliveries and returns");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String getLabel() { return label; }

    public String getDescription() { return description; }

    /** Back-office accounts: everyone except customers. */
    public boolean isStaff() { return this != CUSTOMER; }

    /** Roles the admin can give a new account; there's only ever one admin. */
    public static List<Role> assignable() { return List.of(CUSTOMER, PHARMACIST, SUPPORT, FINANCE, STORE); }
}
