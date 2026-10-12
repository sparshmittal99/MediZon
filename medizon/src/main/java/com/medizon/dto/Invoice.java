package com.medizon.dto;

import com.medizon.domain.Order;
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
    BigDecimal grandTotal;
    String amountInWords;

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

    @Value
    public static class Line {
        int number;
        String description;
        String hsnCode;
        /** Expiry of the batch sold; null for delivery and for orders placed before expiry was recorded. */
        LocalDate expiryDate;
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
