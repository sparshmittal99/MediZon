package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * One kind of error seen by monitoring: every occurrence with the same fingerprint (source, type and where it was
 * thrown) is counted on the same row, which keeps the details of the latest one. Resolved errors reopen if they recur.
 */
@Entity
@Table(name = "app_errors", indexes = @Index(name = "ux_app_errors_fingerprint", columnList = "fingerprint", unique = true))
@Getter @Setter @NoArgsConstructor
public class AppError {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String fingerprint;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ErrorSource source;

    /** Exception class, or the logger name for errors logged without one. */
    @Column(nullable = false, length = 200)
    private String type;

    @Column(length = 1000)
    private String message;

    /** First frame in MediZon's own code (or the script and line, for browser errors). */
    @Column(length = 300)
    private String location;

    @Column(columnDefinition = "text")
    private String stackTrace;

    /** Request of the latest occurrence; empty for scheduled jobs. */
    @Column(length = 300)
    private String path;

    @Column(length = 10)
    private String method;

    private Integer status;

    @Column(length = 120)
    private String lastUser;

    @Column(length = 300)
    private String userAgent;

    /** Shown on the error page and in log lines, to find the occurrence in the logs. */
    @Column(length = 16)
    private String lastRequestId;

    /** Rated by monitoring from what failed and where, unless an admin set it ({@link #severityPinned}). */
    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private ErrorSeverity severity;

    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private boolean severityPinned;

    /** When admins were last alerted about it, so a flapping error doesn't alert over and over. */
    private LocalDateTime alertedAt;

    private long occurrences;

    private LocalDateTime firstSeen;

    private LocalDateTime lastSeen;

    private boolean resolved;

    private LocalDateTime resolvedAt;
}
