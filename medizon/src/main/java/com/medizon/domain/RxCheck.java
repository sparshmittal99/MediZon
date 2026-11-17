package com.medizon.domain;

/**
 * How a prescription medicine ordered without an approved prescription gets one, chosen at checkout: the customer
 * uploads it for the pharmacist to approve, or orders without one and a pharmacist talks to them and issues a digital
 * prescription.
 */
public enum RxCheck {
    UPLOAD("Prescription uploaded"),
    CONSULT("No prescription");

    private final String label;

    RxCheck(String label) { this.label = label; }

    public String getLabel() { return label; }
}
