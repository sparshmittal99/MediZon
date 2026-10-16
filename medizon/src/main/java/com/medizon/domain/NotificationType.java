package com.medizon.domain;

public enum NotificationType {
    DOSE_REMINDER("bi-alarm"),
    REORDER_ALERT("bi-arrow-repeat"),
    AUTO_REORDER("bi-truck"),
    ORDER_UPDATE("bi-box-seam"),
    PRESCRIPTION_UPDATE("bi-file-earmark-medical"),
    RETURN_UPDATE("bi-arrow-return-left"),
    EXPIRY_ALERT("bi-calendar-x"),
    ERROR_ALERT("bi-bug"),
    /** A reply or change on the customer's own support ticket. */
    SUPPORT_UPDATE("bi-headset"),
    /** A ticket for a staff team to look at. */
    SUPPORT_TICKET("bi-headset"),
    INFO("bi-info-circle");

    private final String icon;

    NotificationType(String icon) { this.icon = icon; }

    public String getIcon() { return icon; }
}
