package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.Invoice;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class InvoiceMathTest {

    @Test
    void amountInWordsUsesIndianNumbering() {
        assertThat(RupeesInWords.of(new BigDecimal("125050.50")))
                .isEqualTo("Rupees One Lakh Twenty Five Thousand Fifty and Fifty Paise Only");
        assertThat(RupeesInWords.of(new BigDecimal("33.50"))).isEqualTo("Rupees Thirty Three and Fifty Paise Only");
        assertThat(RupeesInWords.of(new BigDecimal("12000000"))).isEqualTo("Rupees One Crore Twenty Lakh Only");
        assertThat(RupeesInWords.of(BigDecimal.ZERO)).isEqualTo("Rupees Zero Only");
    }

    @Test
    void stateCodesAcceptCommonSpellings() {
        assertThat(GstStates.code("Maharashtra")).isEqualTo("27");
        assertThat(GstStates.code(" tamil nadu ")).isEqualTo("33");
        assertThat(GstStates.code("TamilNadu")).isEqualTo("33");
        assertThat(GstStates.code("New Delhi")).isEqualTo("07");
        assertThat(GstStates.code("Orissa")).isEqualTo("21");
        assertThat(GstStates.code("Atlantis")).isNull();
        assertThat(GstStates.code(null)).isNull();
    }

    @Test
    void financialYearRunsAprilToMarch() {
        assertThat(InvoiceService.financialYear(LocalDate.of(2026, 10, 12))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(LocalDate.of(2027, 3, 31))).isEqualTo("2026-27");
        assertThat(InvoiceService.financialYear(LocalDate.of(2027, 4, 1))).isEqualTo("2027-28");
        assertThat(InvoiceService.financialYear(LocalDate.of(2099, 6, 1))).isEqualTo("2099-00");
    }

    @Test
    void intraStateInvoiceSplitsTaxAndAddsUpToTheOrderTotal() {
        Invoice inv = invoice("Maharashtra", "Maharashtra");
        assertThat(inv.isInterState()).isFalse();
        assertThat(inv.getGrandTotal()).isEqualByComparingTo("540.50");
        assertThat(inv.getTaxableTotal().add(inv.getTaxTotal())).isEqualByComparingTo(inv.getGrandTotal());
        assertThat(inv.getIgstTotal()).isEqualByComparingTo("0");

        Invoice.Line para = inv.getLines().get(0);            // 2 x 33.50 at 5%
        assertThat(para.getTaxableValue()).isEqualByComparingTo("63.81");
        assertThat(para.getCgst().add(para.getSgst())).isEqualByComparingTo("3.19");
        Invoice.Line delivery = inv.getLines().get(2);        // taxed at the highest item rate
        assertThat(delivery.getDescription()).isEqualTo("Delivery charges");
        assertThat(delivery.getGstRate()).isEqualByComparingTo("12");
        assertThat(inv.getTaxSummary()).hasSize(2);
        assertThat(inv.getPlaceOfSupply()).isEqualTo("Maharashtra (27)");
    }

    @Test
    void interStateInvoiceChargesIgst() {
        Invoice inv = invoice("Maharashtra", "Karnataka");
        assertThat(inv.isInterState()).isTrue();
        assertThat(inv.getCgstTotal()).isEqualByComparingTo("0");
        assertThat(inv.getIgstTotal()).isEqualByComparingTo(inv.getTaxTotal());
        assertThat(inv.getTaxableTotal().add(inv.getIgstTotal())).isEqualByComparingTo("540.50");
    }

    private Invoice invoice(String sellerState, String buyerState) {
        InvoiceService service = new InvoiceService(null);
        ReflectionTestUtils.setField(service, "sellerName", "MediZon Pharmacy");
        ReflectionTestUtils.setField(service, "sellerGstin", "");
        ReflectionTestUtils.setField(service, "sellerDrugLicense", "");
        ReflectionTestUtils.setField(service, "sellerAddress", "");
        ReflectionTestUtils.setField(service, "sellerState", sellerState);
        ReflectionTestUtils.setField(service, "sellerPhone", "");
        ReflectionTestUtils.setField(service, "sellerEmail", "");

        Order o = new Order();
        o.setStatus(OrderStatus.SHIPPED);
        o.setInvoiceNumber("MZ/2026-27/00001");
        o.setInvoicedAt(LocalDateTime.now());
        o.setShippingState(buyerState);
        o.addItem(item("Paracetamol 650 mg", "33.50", 2, "5"));
        o.addItem(item("Ibuprofen 400 mg", "433.50", 1, "12"));
        o.setDeliveryFee(new BigDecimal("40"));
        o.setTotal(new BigDecimal("540.50"));
        return service.forOrder(o);
    }

    private static OrderItem item(String name, String price, int qty, String rate) {
        OrderItem i = new OrderItem();
        i.setMedicineName(name);
        i.setUnitPrice(new BigDecimal(price));
        i.setQuantity(qty);
        i.setLineTotal(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        i.setGstRate(new BigDecimal(rate));
        i.setHsnCode("3004");
        return i;
    }
}
