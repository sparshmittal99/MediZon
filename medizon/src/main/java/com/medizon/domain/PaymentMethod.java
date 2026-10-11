package com.medizon.domain;

public enum PaymentMethod {
    COD("Cash on delivery"), UPI("UPI"), CARD("Debit / credit card");

    private final String label;

    PaymentMethod(String label) { this.label = label; }

    public String getLabel() { return label; }
}
