package com.medizon.domain;

import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** A photo attached to a return request; stored privately and streamed to the owner and admins only. */
@Embeddable
@Getter @NoArgsConstructor @AllArgsConstructor
public class ReturnImage {
    private String storedFileName;
    private String contentType;
}
