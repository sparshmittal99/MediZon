package com.medizon.service;

import com.medizon.domain.*;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import javax.imageio.ImageIO;
import java.io.File;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The emailed invoice PDF: a real PDF with the invoice's numbers, the rupee sign, and a file name safe for mail. */
class InvoicePdfServiceTest {

    private static TemplateEngine engine() {
        ClassLoaderTemplateResolver r = new ClassLoaderTemplateResolver();
        r.setPrefix("templates/");
        r.setSuffix(".html");
        r.setTemplateMode(TemplateMode.HTML);
        r.setCharacterEncoding("UTF-8");
        TemplateEngine e = new SpringTemplateEngine();
        e.setTemplateResolver(r);
        return e;
    }

    private static InvoiceService invoices() {
        InvoiceService s = new InvoiceService(null);
        ReflectionTestUtils.setField(s, "sellerName", "MediZon Pharmacy");
        ReflectionTestUtils.setField(s, "sellerGstin", "27AABCM4521K1Z6");
        ReflectionTestUtils.setField(s, "sellerDrugLicense", "20B/MH-MZU/104512");
        ReflectionTestUtils.setField(s, "sellerAddress", "Shop 4, Green Park Plaza, Linking Road, Bandra West, Mumbai 400050");
        ReflectionTestUtils.setField(s, "sellerState", "Maharashtra");
        ReflectionTestUtils.setField(s, "sellerPhone", "+91 98200 45120");
        ReflectionTestUtils.setField(s, "sellerEmail", "support@medizon.in");
        return s;
    }

    private static OrderItem item(String name, String price, int qty, String rate) {
        OrderItem i = new OrderItem();
        i.setMedicineName(name);
        i.setUnitPrice(new BigDecimal(price));
        i.setQuantity(qty);
        i.setLineTotal(new BigDecimal(price).multiply(BigDecimal.valueOf(qty)));
        i.setGstRate(new BigDecimal(rate));
        i.setHsnCode("30049099");
        return i;
    }

    private static Order order() {
        User u = new User();
        u.setFullName("Sparsh Mittal");
        Order o = new Order();
        o.setUser(u);
        o.setOrderNumber("MZ26102867325");
        o.setStatus(OrderStatus.SHIPPED);
        o.setInvoiceNumber("MZ/2026-27/00031");
        o.setInvoicedAt(LocalDateTime.of(2026, 10, 28, 11, 0));
        o.setShippingState("Maharashtra");
        o.setShippingAddress("f-10, Ghansoli, Navi Mumbai, Maharashtra, 400701");
        o.setPaymentMethod(PaymentMethod.UPI);
        o.setPaymentStatus(PaymentStatus.PAID);
        o.addItem(item("Metformin 500 mg", "68.00", 2, "12"));
        o.addItem(item("Paracetamol 650 mg", "33.50", 1, "5"));
        o.setDeliveryFee(new BigDecimal("40"));
        o.setTotal(new BigDecimal("209.50"));
        return o;
    }

    @Test
    void rendersTheInvoiceAsAPdf() throws Exception {
        byte[] pdf = new InvoicePdfService(invoices(), engine()).render(order());

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("TAX INVOICE", "MZ/2026-27/00031", "MZ26102867325", "Metformin 500 mg",
                    "Delivery charges", "Sparsh Mittal", "₹209.50", "Rupees Two Hundred Nine and Fifty Paise Only");
            // A look at the page while developing: mvn test -Dmz.invoicePng=path/to/file.png
            String png = System.getProperty("mz.invoicePng");
            if (png != null) ImageIO.write(new PDFRenderer(doc).renderImageWithDPI(0, 110), "png", new File(png));
        }
    }

    @Test
    void emailBodyRenders() {
        org.thymeleaf.context.Context ctx = new org.thymeleaf.context.Context();
        ctx.setVariables(java.util.Map.of("firstName", "Sparsh", "invoiceNumber", "MZ/2026-27/00031", "orderNumber", "MZ26102867325",
                "invoiceDate", "28 Oct 2026", "total", "₹209.50", "orderUrl", "https://example.test/orders/1"));
        String html = engine().process("emails/invoice", ctx);
        assertThat(html).contains("Hi <span>Sparsh</span>", "MZ/2026-27/00031", "₹209.50", "https://example.test/orders/1", "cid:medizonLogo");
    }

    @Test
    void fileNameHasNoSlashes() {
        assertThat(InvoicePdfService.fileName(order())).isEqualTo("MediZon-invoice-MZ-2026-27-00031.pdf");
    }

    @Test
    void emailsAreLimitedPerOrderPerDay() {
        InvoiceEmailService mail = new InvoiceEmailService(null, null);
        for (int i = 0; i < InvoiceEmailService.PER_ORDER_PER_DAY; i++) mail.reserve(7L);
        assertThrows(BusinessException.class, () -> mail.reserve(7L));
        mail.reserve(8L);   // another order is counted on its own
    }
}
