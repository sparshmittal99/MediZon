package com.medizon.domain;

public enum PaymentMethod {
    COD("Cash on delivery"), UPI("UPI"), CARD("Debit / credit card"),
    /** The whole order was paid with MediZon Money; never offered on its own at checkout. */
    WALLET("MediZon Money");

    private final String label;

    PaymentMethod(String label) { this.label = label; }

    public String getLabel() { return label; }

    /** Ways to pay what MediZon Money doesn't cover. */
    public static PaymentMethod[] payable() { return new PaymentMethod[]{COD, UPI, CARD}; }
}
