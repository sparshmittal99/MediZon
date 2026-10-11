package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * Traffic for one day. A visit is one browser session per day; a page view is every page it loads.
 * Written by {@link com.medizon.web.VisitInterceptor}.
 */
@Entity
@Table(name = "daily_visits")
@Getter @Setter @NoArgsConstructor
public class DailyVisit {

    @Id
    private LocalDate day;

    private long visits;
    private long pageViews;

    public DailyVisit(LocalDate day) { this.day = day; }
}
