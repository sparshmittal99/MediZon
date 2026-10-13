package com.medizon.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The one address invoices are billed to. It has nothing to do with delivery: orders go to a saved address.
 * Without one, the order is billed to the address it's delivered to.
 */
@Embeddable
@Getter @Setter @NoArgsConstructor
public class BillingAddress {

    @Column(name = "billing_name", length = 80)
    private String name;

    @Column(name = "billing_address_line", length = 300)
    private String addressLine;

    @Column(name = "billing_city", length = 80)
    private String city;

    @Column(name = "billing_state", length = 60)
    private String state;

    @Column(name = "billing_pincode", length = 6)
    private String pincode;

    /** Optional, for business purchases; printed on the invoice. */
    @Column(name = "billing_gstin", length = 15)
    private String gstin;

    public boolean isSet() {
        return name != null && !name.isBlank() && addressLine != null && !addressLine.isBlank();
    }

    public String getFormatted() {
        return Stream.of(addressLine, city, state, pincode)
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }
}
