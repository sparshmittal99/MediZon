package com.medizon.domain;

public enum NotificationType {
    DOSE_REMINDER("bi-alarm"),
    REORDER_ALERT("bi-arrow-repeat"),
    AUTO_REORDER("bi-truck"),
    ORDER_UPDATE("bi-box-seam"),
    PRESCRIPTION_UPDATE("bi-file-earmark-medical"),
    RETURN_UPDATE("bi-arrow-return-left"),
    EXPIRY_ALERT("bi-calendar-x"),
    INFO("bi-info-circle");

    private final String icon;

    NotificationType(String icon) { this.icon = icon; }

    public String getIcon() { return icon; }
}
