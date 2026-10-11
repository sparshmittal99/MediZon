package com.medizon.dto;

import java.time.LocalDate;

/** One row of the admin dashboard's "last 7 days" table. */
public record DayTraffic(LocalDate day, long visits, long pageViews, long signups) {}
