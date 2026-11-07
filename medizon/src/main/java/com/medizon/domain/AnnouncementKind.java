package com.medizon.domain;

/** What an admin announcement is about: sets its label, icon and the email's heading. */
public enum AnnouncementKind {
    SALE("Sale", "bi-tag", "Big savings on medicines and essentials this weekend."),
    DISCOUNT("Discount", "bi-percent", "Flat 15% off your next order."),
    OFFER("Offer", "bi-gift", "Free delivery on all orders today."),
    NEW_ARRIVAL("New arrival", "bi-stars", "New wellness range now in stock."),
    FESTIVE("Festive", "bi-balloon", "Celebrate with special festive prices."),
    HEALTH_TIP("Health tip", "bi-heart-pulse", "Stay hydrated: drink 8 glasses of water a day."),
    UPDATE("App update", "bi-phone", "New: track your refills from the home screen."),
    GENERAL("General", "bi-megaphone", "An important note for our customers.");

    private final String label;
    private final String icon;
    private final String example;

    AnnouncementKind(String label, String icon, String example) {
        this.label = label;
        this.icon = icon;
        this.example = example;
    }

    public String getLabel() { return label; }
    public String getIcon() { return icon; }
    public String getExample() { return example; }
}
