package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * How returns run, set by the admin: how long customers have to ask, when pickups are booked, and how long each kind of
 * refund takes after pickup. One row; MediZon Money refunds are always instant.
 */
@Entity
@Table(name = "return_settings")
@Getter @Setter @NoArgsConstructor
public class ReturnSettings {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    /** Days after delivery a return can still be asked for. */
    private int returnWindowDays = 7;

    /** A pickup is booked this many days after the request: a day in the range, for now picked at random. */
    private int pickupMinDays = 1;
    private int pickupMaxDays = 3;

    /** Days after pickup the refund lands, by where it goes. */
    private int upiRefundDays = 2;
    private int bankRefundDays = 7;
    private int cardRefundDays = 7;

    private LocalDateTime updatedAt;

    @Column(length = 120)
    private String updatedBy;

    /** The settings before the admin has changed anything. */
    public static ReturnSettings defaults() { return new ReturnSettings(); }
}
