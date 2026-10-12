package com.medizon.domain;

/** Which days a plan's doses fall on. */
public enum ScheduleType {
    EVERY_DAY("Every day"),
    DAYS_OF_WEEK("On certain days of the week"),
    CUSTOM_DATES("On specific dates");

    private final String label;

    ScheduleType(String label) { this.label = label; }

    public String getLabel() { return label; }
}
