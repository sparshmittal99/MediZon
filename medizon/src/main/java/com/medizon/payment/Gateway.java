package com.medizon.payment;

/** Who took an online payment. */
public enum Gateway {
    RAZORPAY("Razorpay"),
    /** MediZon's own UPI checkout in test mode: UPI apps, UPI ID and QR, with no real money (see {@link MediZonUpiGateway}). */
    MEDIZON_UPI("MediZon UPI");

    private final String label;

    Gateway(String label) { this.label = label; }

    public String getLabel() { return label; }
}
