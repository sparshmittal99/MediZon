package com.medizon.domain;

/** What a saved address is called in pickers: home, office, or a name the customer gives it. */
public enum AddressType {
    HOME("Home", "bi-house-door"), OFFICE("Office", "bi-building"), OTHER("Other", "bi-geo-alt");

    private final String label;
    private final String icon;

    AddressType(String label, String icon) {
        this.label = label;
        this.icon = icon;
    }

    public String getLabel() { return label; }

    public String getIcon() { return icon; }
}
