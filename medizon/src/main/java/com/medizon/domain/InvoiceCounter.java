package com.medizon.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Last invoice serial used in a financial year ("2026-27"). GST invoices are numbered consecutively per year. */
@Entity
@Table(name = "invoice_counters")
@Getter @Setter @NoArgsConstructor
public class InvoiceCounter {

    @Id
    private String financialYear;

    private long lastNumber;
}
