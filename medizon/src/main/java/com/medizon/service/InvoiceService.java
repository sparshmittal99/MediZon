package com.medizon.service;

import com.medizon.domain.InvoiceCounter;
import com.medizon.domain.Order;
import com.medizon.domain.OrderItem;
import com.medizon.domain.Store;
import com.medizon.dto.Invoice;
import com.medizon.repository.InvoiceCounterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * GST tax invoices. Prices on MediZon include GST, so each line's taxable value is worked back out of the price paid:
 * taxable = price x 100 / (100 + rate). Delivery charges are part of a composite supply and are taxed at the rate of
 * the order's principal supply (its highest-rated medicine). A promo discount is given on the invoice itself, so it
 * comes off each line's value before tax is worked back out.
 */
@Service
@RequiredArgsConstructor
public class InvoiceService {

    /** Rate used for lines from orders placed before medicines carried a GST rate. */
    private static final BigDecimal DEFAULT_RATE = new BigDecimal("5");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final InvoiceCounterRepository counters;

    @Value("${medizon.seller.name:MediZon Pharmacy}") private String sellerName;
    @Value("${medizon.seller.gstin:}") private String sellerGstin;
    @Value("${medizon.seller.drug-license:}") private String sellerDrugLicense;
    @Value("${medizon.seller.address:}") private String sellerAddress;
    @Value("${medizon.seller.state:}") private String sellerState;
    @Value("${medizon.seller.phone:}") private String sellerPhone;
    @Value("${medizon.seller.email:}") private String sellerEmail;

    /** Gives the order the next invoice number of the current financial year, once. */
    @Transactional
    public void issue(Order order) {
        if (order.getInvoiceNumber() != null) return;
        String fy = financialYear(LocalDate.now());
        counters.ensureYear(fy);
        InvoiceCounter c = counters.lock(fy).orElseThrow();
        c.setLastNumber(c.getLastNumber() + 1);
        order.setInvoiceNumber(String.format("MZ/%s/%05d", fy, c.getLastNumber()));   // 16 characters, the GST limit
        order.setInvoicedAt(LocalDateTime.now());
    }

    /** April to March: "2026-27". */
    static String financialYear(LocalDate day) {
        int start = day.getMonthValue() >= 4 ? day.getYear() : day.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    @Transactional
    public Invoice forOrder(Order o) {
        if (!o.isInvoiceAvailable()) throw new BusinessException("The tax invoice is issued once the order ships.");
        issue(o);   // orders that shipped before invoices existed get their number on first view
        return breakdown(o);
    }

    /** The tax worked out for any order, without issuing an invoice number; used by the sales export too. */
    public Invoice breakdown(Order o) {
        Invoice.Seller seller = seller(o.getStore());
        String buyerState = o.getShippingState() != null ? o.getShippingState() : o.getUser().getState();
        String buyerCode = GstStates.code(buyerState);
        boolean interState = seller.getStateCode() != null && buyerCode != null && !seller.getStateCode().equals(buyerCode);

        List<Invoice.Line> lines = new ArrayList<>();
        BigDecimal topRate = BigDecimal.ZERO;
        int n = 1;
        for (OrderItem i : o.getItems()) {
            BigDecimal rate = i.getGstRate() != null ? i.getGstRate() : DEFAULT_RATE;
            topRate = topRate.max(rate);
            String hsn = i.getHsnCode() != null ? i.getHsnCode() : i.getMedicine().getHsnCode();
            BigDecimal off = i.getDiscount() == null ? BigDecimal.ZERO : i.getDiscount();
            lines.add(line(n++, i.getMedicineName(), hsn, i.getExpiryDate(), i.getPatientName(), i.getPrescribedBy(), i.getQuantity(), i.getUnitPrice(), rate, off, i.getNetTotal(), interState));
        }
        if (o.getDeliveryFee() != null && o.getDeliveryFee().signum() > 0) {
            lines.add(line(n, "Delivery charges", "", null, null, null, 1, o.getDeliveryFee(), topRate, BigDecimal.ZERO, o.getDeliveryFee(), interState));
        }

        Map<BigDecimal, BigDecimal[]> byRate = new TreeMap<>();
        BigDecimal taxable = BigDecimal.ZERO, cgst = BigDecimal.ZERO, sgst = BigDecimal.ZERO, igst = BigDecimal.ZERO, total = BigDecimal.ZERO;
        for (Invoice.Line l : lines) {
            taxable = taxable.add(l.getTaxableValue());
            cgst = cgst.add(l.getCgst());
            sgst = sgst.add(l.getSgst());
            igst = igst.add(l.getIgst());
            total = total.add(l.getTotal());
            BigDecimal[] row = byRate.computeIfAbsent(l.getGstRate().stripTrailingZeros(),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO});
            row[0] = row[0].add(l.getTaxableValue());
            row[1] = row[1].add(l.getCgst());
            row[2] = row[2].add(l.getSgst());
            row[3] = row[3].add(l.getIgst());
        }
        List<Invoice.TaxRow> summary = byRate.entrySet().stream()
                .map(e -> new Invoice.TaxRow(e.getKey(), e.getValue()[0], e.getValue()[1], e.getValue()[2], e.getValue()[3]))
                .toList();

        String place = buyerState == null || buyerState.isBlank() ? "Not given"
                : buyerState + (buyerCode == null ? "" : " (" + buyerCode + ")");
        // Rounding is decided when the order is placed, so the invoice shows exactly what the customer paid
        BigDecimal roundOff = (o.getRoundOff() == null ? BigDecimal.ZERO : o.getRoundOff()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grand = total.add(roundOff);
        BigDecimal discount = lines.stream().map(Invoice.Line::getDiscount).reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
        return new Invoice(o, seller, buyerState, buyerCode, place, interState, lines, summary,
                taxable, cgst, sgst, igst, cgst.add(sgst).add(igst), total, roundOff, grand, RupeesInWords.of(grand),
                discount, o.getCouponCode());
    }

    /**
     * Splits a GST-inclusive amount into taxable value and tax; the parts always add back up to the amount paid.
     * {@code gross} is after the {@code discount}.
     */
    private static Invoice.Line line(int n, String description, String hsn, LocalDate expiry, String patient, String prescribedBy, int qty, BigDecimal unitPrice, BigDecimal rate,
                                     BigDecimal discount, BigDecimal gross, boolean interState) {
        BigDecimal taxable = gross.multiply(HUNDRED).divide(HUNDRED.add(rate), 2, RoundingMode.HALF_UP);
        BigDecimal tax = gross.subtract(taxable);
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        BigDecimal cgst = zero, sgst = zero, igst = zero;
        if (interState) {
            igst = tax;
        } else {
            cgst = tax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
            sgst = tax.subtract(cgst);
        }
        return new Invoice.Line(n, description, hsn, expiry, patient, prescribedBy, qty, unitPrice, discount.setScale(2, RoundingMode.HALF_UP),
                rate.stripTrailingZeros(), taxable, cgst, sgst, igst, gross.setScale(2, RoundingMode.HALF_UP));
    }

    /** MediZon's own details from configuration: the site footer and reports. */
    public Invoice.Seller seller() {
        return new Invoice.Seller(sellerName, sellerGstin, sellerDrugLicense, sellerAddress, sellerState,
                GstStates.code(sellerState), sellerPhone, sellerEmail);
    }

    /**
     * Who sells an order: always MediZon by name, with the address, GSTIN, licence and contacts of the store that
     * fulfils it (MediZon's configured details for anything the store hasn't filled in). The seller's state decides
     * CGST + SGST or IGST, so each store's invoices follow its own state.
     */
    public Invoice.Seller seller(Store s) {
        if (s == null) return seller();
        String state = or(s.getState(), sellerState);
        return new Invoice.Seller(sellerName, or(s.getGstin(), sellerGstin), or(s.getDrugLicense(), sellerDrugLicense),
                or(s.getStreetAddress(), sellerAddress), state, GstStates.code(state), or(s.getPhone(), sellerPhone),
                or(s.getEmail(), sellerEmail));
    }

    private static String or(String value, String fallback) {
        return value == null || value.isBlank() ? (fallback == null ? "" : fallback) : value;
    }
}
