package com.medizon.domain;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * A pharmacy store that sells and delivers. The admin decides which PIN codes each store serves ({@link StoreArea});
 * the store's own accounts keep its catalogue ({@link StoreItem}) and handle its orders and returns. A store's legal
 * details are printed on the invoices for its orders; blank ones fall back to the seller details in configuration.
 */
@Entity
@Table(name = "stores")
@Getter @Setter @NoArgsConstructor
public class Store {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "Enter the store's name")
    @Column(nullable = false, length = 80)
    private String name;

    private String addressLine;
    private String city;
    private String state;

    @Pattern(regexp = "^$|[1-9][0-9]{5}", message = "Enter a 6 digit PIN code")
    @Column(length = 6)
    private String pincode;

    private String phone;
    private String email;

    /** Printed on invoices for this store's orders. */
    @Pattern(regexp = "^$|[0-9A-Z]{15}", message = "A GSTIN is 15 letters and digits")
    @Column(length = 15)
    private String gstin;

    private String drugLicense;

    /** A paused store sells nothing and delivers nowhere; its orders can still be handled. */
    private boolean active = true;

    private LocalDateTime createdAt = LocalDateTime.now();

    public String getFormattedAddress() {
        return Stream.of(addressLine, city, state, pincode).filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }
}
