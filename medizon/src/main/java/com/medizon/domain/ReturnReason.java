package com.medizon.domain;

public enum ReturnReason {
    DAMAGED("Damaged or leaking pack"),
    WRONG_ITEM("Wrong medicine or strength delivered"),
    EXPIRED("Expired or close to expiry"),
    QUALITY("Quality problem (broken seal, discoloured ...)"),
    NOT_NEEDED("Ordered by mistake or no longer needed"),
    OTHER("Something else");

    private final String label;

    ReturnReason(String label) { this.label = label; }

    public String getLabel() { return label; }
}
