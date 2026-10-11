package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter @Setter @NoArgsConstructor
public class Prescription {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Column(nullable = false)
    private String storedFileName;
    private String originalFileName;
    private String contentType;

    private String doctorName;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    private PrescriptionStatus status = PrescriptionStatus.PENDING;

    @Column(length = 1000)
    private String reviewerNote;

    private LocalDateTime uploadedAt = LocalDateTime.now();
    private LocalDateTime reviewedAt;
    private LocalDate validUntil;

    /** Approved and not expired: usable for Rx orders and auto-refills. */
    public boolean isValid() {
        return status == PrescriptionStatus.APPROVED
                && (validUntil == null || !validUntil.isBefore(LocalDate.now()));
    }

    public boolean isImage() { return contentType != null && contentType.startsWith("image/"); }

    public String getLabel() {
        String d = doctorName == null || doctorName.isBlank() ? "Prescription" : "Dr. " + doctorName;
        return d + " (uploaded " + uploadedAt.toLocalDate() + ")";
    }
}
