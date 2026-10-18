package com.medizon.dto;

import com.medizon.domain.Order;
import com.medizon.domain.RoundOff;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A GST tax invoice for one order, worked out from its GST-inclusive prices. Intra-state supplies split the tax into
 * CGST and SGST; inter-state supplies charge IGST.
 */
@Value
public class Invoice {

    Order order;
    Seller seller;
    String buyerState;
    String buyerStateCode;
    /** Place of supply, e.g. "Maharashtra (27)". */
    String placeOfSupply;
    boolean interState;
    List<Line> lines;
    List<TaxRow> taxSummary;
    BigDecimal taxableTotal;
    BigDecimal cgstTotal;
    BigDecimal sgstTotal;
    BigDecimal igstTotal;
    BigDecimal taxTotal;
    /** Taxable value plus tax, before rounding. */
    BigDecimal invoiceValue;
    /** Paise added or taken off to reach whole rupees; zero on orders from before rounding. */
    BigDecimal roundOff;
    /** What the customer pays: the invoice value plus the round off. */
    BigDecimal grandTotal;
    String amountInWords;

    public boolean isRounded() { return roundOff.signum() != 0; }

    public String getRoundOffLabel() { return RoundOff.label(roundOff); }

    @Value
    public static class Seller {
        String name;
        String gstin;
        String drugLicense;
        String address;
        String state;
        String stateCode;
        String phone;
        String email;

        public boolean isComplete() {
            return !gstin.isBlank() && !address.isBlank() && !state.isBlank();
        }
    }

    /** Each prescription the order used, for the invoice header: "Dr. Ambrose, 4 Oct 2026". */
    public List<String> getPrescriptions() {
        return lines.stream().map(Line::getPrescribedBy).filter(n -> n != null && !n.isBlank())
                .flatMap(n -> java.util.Arrays.stream(n.split(";"))).map(String::trim).filter(n -> !n.isEmpty())
                .distinct().toList();
    }

    /** Everyone the medicines are for, for the invoice header: "Asha Sharma, Ravi Kumar"; empty when none is recorded. */
    public String getPatients() {
        return lines.stream().map(Line::getPatientName).filter(n -> n != null && !n.isBlank())
                .flatMap(n -> java.util.Arrays.stream(n.split(",\\s*"))).distinct()
                .collect(java.util.stream.Collectors.joining(", "));
    }

    @Value
    public static class Line {
        int number;
        String description;
        String hsnCode;
        /** Expiry of the batch sold; null for delivery and for orders placed before expiry was recorded. */
        LocalDate expiryDate;
        /** Who the medicine was for; null for delivery and for orders placed before patients were recorded. */
        String patientName;
        /** Who prescribed it and when ("Dr. Ambrose, 4 Oct 2026"); null for OTC medicines and older orders. */
        String prescribedBy;
        int quantity;
        /** Price per pack, GST included. */
        BigDecimal unitPrice;
        BigDecimal gstRate;
        BigDecimal taxableValue;
        BigDecimal cgst;
        BigDecimal sgst;
        BigDecimal igst;
        /** Taxable value plus tax: what the customer paid for the line. */
        BigDecimal total;
    }

    /** Tax per GST rate, the summary block printed under the lines. */
    @Value
    public static class TaxRow {
        BigDecimal gstRate;
        BigDecimal taxableValue;
        BigDecimal cgst;
        BigDecimal sgst;
        BigDecimal igst;
    }
}
