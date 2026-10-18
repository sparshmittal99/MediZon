package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** The patient as written on it (read by AI, confirmed by the pharmacist): which member its medicines are for. */
    @Column(length = 100)
    private String patientName;

    /** The date the doctor wrote it (read by AI, confirmed by the pharmacist), printed on invoices. */
    private LocalDate prescribedOn;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    private PrescriptionStatus status = PrescriptionStatus.PENDING;

    @Column(length = 1000)
    private String reviewerNote;

    private LocalDateTime uploadedAt = LocalDateTime.now();
    private LocalDateTime reviewedAt;
    private LocalDate validUntil;

    /** The AI's reading of the file as JSON ({@code PrescriptionReading}); null if not read or it couldn't be. */
    @Column(columnDefinition = "text")
    private String aiReading;

    /** When the AI last tried to read it; set with no reading means it failed. */
    private LocalDateTime aiReadAt;

    /** The medicines the AI read, one line, for the pharmacist's queue. */
    @Column(length = 1000)
    private String aiSummary;

    /** The reading's score out of 100 ({@code PrescriptionScore}), for the pharmacist and admin; null if not read. */
    private Integer aiScore;

    /** Scored high enough with no concerns: top of the pharmacist's queue, one-click approval. */
    @Column(columnDefinition = "boolean default false not null")    // existing rows get false
    private boolean fastTrack;

    /**
     * Removed by the customer after it was used for an order: hidden from them and never used again, but kept with
     * its file because the orders it was dispensed against refer to it. Never-used ones are deleted instead.
     */
    @Column(columnDefinition = "boolean default false not null")
    private boolean removed;

    /**
     * The medicines it covers and how many packs, as the pharmacist approved. Empty only on prescriptions approved
     * before quantities were recorded, which can no longer be ordered with ({@link #isLegacy()}).
     */
    @OneToMany(mappedBy = "prescription", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id")
    private List<PrescriptionItem> items = new ArrayList<>();

    /**
     * Packs of each medicine (by id) the customer had in their cart for this patient when they sent it: pre-fills the
     * pharmacist's list alongside what the AI read, so the pharmacist only checks it against the file.
     */
    @ElementCollection
    @CollectionTable(name = "prescription_requested", joinColumns = @JoinColumn(name = "prescription_id"))
    @MapKeyColumn(name = "medicine_id")
    @Column(name = "packs")
    private Map<Long, Integer> requestedPacks = new LinkedHashMap<>();

    /**
     * Usable for Rx orders and auto-refills: approved with its medicines recorded, not expired and not used up.
     * Prescriptions approved before medicines were recorded stay on record but can't be ordered with again.
     */
    public boolean isValid() {
        return status == PrescriptionStatus.APPROVED && isLimited() && !removed
                && (validUntil == null || !validUntil.isBefore(LocalDate.now()));
    }

    /** Approved before the pharmacist recorded its medicines: kept on record, but a new prescription is needed to order. */
    public boolean isLegacy() { return status == PrescriptionStatus.APPROVED && !isLimited(); }

    /** Covers only the medicines listed on it (every prescription approved since quantities were recorded). */
    public boolean isLimited() { return !items.isEmpty(); }

    public Optional<PrescriptionItem> itemFor(Long medicineId) {
        return items.stream().filter(i -> i.getMedicine().getId().equals(medicineId)).findFirst();
    }

    /** Packs of this medicine that can still be ordered with it. */
    public int remainingFor(Long medicineId) {
        return itemFor(medicineId).map(PrescriptionItem::getRemaining).orElse(0);
    }

    /** Every prescription medicine on it has been ordered in full. */
    public boolean isFullyOrdered() {
        List<PrescriptionItem> rx = items.stream().filter(i -> i.getMedicine().isPrescriptionRequired()).toList();
        return !rx.isEmpty() && rx.stream().allMatch(PrescriptionItem::isUsedUp);
    }

    public boolean isImage() { return contentType != null && contentType.startsWith("image/"); }

    public String getLabel() {
        String d = doctorName == null || doctorName.isBlank() ? "Prescription" : "Dr. " + doctorName;
        return d + " (uploaded " + uploadedAt.toLocalDate() + ")";
    }
}
