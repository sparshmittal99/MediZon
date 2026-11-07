package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The day's health quote, sent once to customers' phones. One row per day, so a restart (or a second server) never
 * sends it twice. Written by {@link com.medizon.service.DailyHealthQuoteService}.
 */
@Entity
@Table(name = "daily_quotes")
@Getter @Setter @NoArgsConstructor
public class DailyQuote {

    @Id
    private LocalDate day;

    @Column(nullable = false, length = 300)
    private String quote;

    /** How many phones it went to. */
    private int phones;

    private LocalDateTime sentAt = LocalDateTime.now();

    public DailyQuote(LocalDate day, String quote) {
        this.day = day;
        this.quote = quote;
    }
}
