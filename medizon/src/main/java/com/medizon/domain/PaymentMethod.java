package com.medizon.domain;

public enum PaymentMethod {
    /** Paid when the order arrives: cash, UPI to the delivery partner or whatever they take. */
    COD("Pay on delivery"),
    UPI("UPI"), CARD("Credit / debit card"), NETBANKING("Net banking"),
    /** Orders from when UPI at the door was a separate choice; now part of {@link #COD}. */
    UPI_ON_DELIVERY("UPI on delivery"),
    /** Orders from when credit and debit cards were separate choices; new card payments are {@link #CARD}. */
    CREDIT_CARD("Credit card"), DEBIT_CARD("Debit card"),
    /** The whole order was paid with MediZon Money; never offered on its own at checkout. */
    WALLET("MediZon Money");

    private final String label;

    PaymentMethod(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Paid online through a payment gateway before the order is placed. */
    public boolean isOnline() {
        return this == UPI || this == CARD || this == NETBANKING || this == CREDIT_CARD || this == DEBIT_CARD;
    }

    /** Paid when the order arrives, so nothing is taken until delivery and a return has nothing online to reverse. */
    public boolean isOnDelivery() { return this == COD || this == UPI_ON_DELIVERY; }

    /** What can be paid online, in the order the payment page lists them. */
    public static PaymentMethod[] online() { return new PaymentMethod[]{UPI, CARD, NETBANKING}; }
}
