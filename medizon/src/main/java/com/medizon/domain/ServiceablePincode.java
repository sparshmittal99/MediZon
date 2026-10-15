package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * A PIN code MediZon delivers to. Admins add them and choose which stores serve each ({@link StoreArea}); an address
 * can check out when its PIN code is active and an open store serves it.
 */
@Entity
@Getter @Setter @NoArgsConstructor
public class ServiceablePincode {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 6)
    private String pincode;

    /** Area or locality, e.g. "Bandra West". Optional, shown to admins and in "Delivers to". */
    private String area;

    private String city;

    private String state;

    /** Paused PIN codes stay listed but aren't delivered to. */
    @Column(columnDefinition = "boolean not null default true")
    private boolean active = true;

    private LocalDateTime createdAt = LocalDateTime.now();
}
