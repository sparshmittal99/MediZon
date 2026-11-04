package com.medizon.service;

import com.medizon.domain.Order;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * The tax invoice as a PDF file, for emailing: templates/pdf/invoice.html rendered by Thymeleaf, then turned into a
 * PDF with OpenHTMLtoPDF. Noto Sans is embedded so the rupee sign and Indian names print the same everywhere.
 * Call it where the order's lazy data can still be read (a web request).
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final String FONT = "Noto Sans";
    private static final String LOGO = logoDataUri();

    private final InvoiceService invoiceService;
    private final TemplateEngine templateEngine;

    public byte[] render(Order order) {
        Context ctx = new Context();
        ctx.setVariable("inv", invoiceService.forOrder(order));
        ctx.setVariable("logo", LOGO);
        String html = templateEngine.process("pdf/invoice", ctx);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024)) {
            PdfRendererBuilder pdf = new PdfRendererBuilder();
            pdf.useFont(() -> font("NotoSans-Regular.ttf"), FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("NotoSans-Bold.ttf"), FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.withHtmlContent(html, null);
            pdf.toStream(out);
            pdf.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't make the invoice PDF for order " + order.getId(), e);
        }
    }

    /** "MediZon-invoice-MZ-2026-27-00031.pdf" */
    public static String fileName(Order order) {
        String number = order.getInvoiceNumber() == null ? order.getOrderNumber() : order.getInvoiceNumber();
        return "MediZon-invoice-" + number.replaceAll("[^A-Za-z0-9-]+", "-") + ".pdf";
    }

    private static InputStream font(String file) {
        InputStream in = InvoicePdfService.class.getResourceAsStream("/fonts/" + file);
        if (in == null) throw new IllegalStateException("Missing font /fonts/" + file);
        return in;
    }

    private static String logoDataUri() {
        try (InputStream in = InvoicePdfService.class.getResourceAsStream("/static/logo-mark.png")) {
            return in == null ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }
}
