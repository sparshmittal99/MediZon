package com.medizon.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A photo on a ticket message; stored privately and streamed only to the customer and the staff on the ticket. */
@Embeddable
@Getter @NoArgsConstructor @AllArgsConstructor
public class TicketAttachment {
    private String storedFileName;
    private String contentType;
}
