package com.medizon.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * One of a customer's saved delivery addresses. An order can go to the customer or to someone else, so each
 * address carries who receives the parcel and their phone number.
 */
@Entity
@Table(name = "addresses")
@Getter @Setter @NoArgsConstructor
public class Address {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false, fetch = FetchType.LAZY)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AddressType type = AddressType.HOME;

    /** The customer's own name for an "Other" address, e.g. "Mom's place". */
    @Column(length = 40)
    private String customLabel;

    /** Delivered to someone other than the account holder. */
    private boolean forSomeoneElse;

    @Column(nullable = false, length = 80)
    private String receiverName;

    @Column(nullable = false, length = 15)
    private String receiverPhone;

    /** Flat or house number, floor, building. */
    @Column(nullable = false, length = 200)
    private String addressLine;

    /** Street, area or locality. */
    @Column(length = 200)
    private String area;

    @Column(length = 120)
    private String landmark;

    @Column(nullable = false, length = 80)
    private String city;

    @Column(length = 60)
    private String state;

    @Column(nullable = false, length = 6)
    private String pincode;

    /** The address automatic refills go to and checkout starts on. */
    private boolean defaultAddress;

    private LocalDateTime createdAt = LocalDateTime.now();

    /** "Home", "Office", or the name given to an "Other" address. */
    public String getLabel() {
        return type == AddressType.OTHER && customLabel != null && !customLabel.isBlank() ? customLabel : type.getLabel();
    }

    /** Address line, area and landmark: the part before city, state and PIN code. */
    public String getStreet() {
        return Stream.of(addressLine, area, landmark == null || landmark.isBlank() ? null : "Near " + landmark)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }

    public String getFormatted() {
        return Stream.of(getStreet(), city, state, pincode)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }
}
