package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** One scheduled dose. Its unique key also stops the scheduler from reminding twice. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"plan_id", "scheduled_at"}))
@Getter @Setter @NoArgsConstructor
public class DoseLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private MedicationPlan plan;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Enumerated(EnumType.STRING)
    private DoseStatus status = DoseStatus.PENDING;

    private LocalDateTime respondedAt;

    public DoseLog(MedicationPlan plan, LocalDateTime scheduledAt) {
        this.plan = plan;
        this.scheduledAt = scheduledAt;
    }
}
