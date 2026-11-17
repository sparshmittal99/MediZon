package com.medizon.service;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.List;

/** A digital prescription a MediZon pharmacist issues, as a PDF: saved as its file, like an uploaded prescription's. */
@Service
@RequiredArgsConstructor
public class PrescriptionPdfService {

    private final TemplateEngine templateEngine;

    /** What the PDF shows. */
    public record Sheet(String patient, String customer, String issuedBy, LocalDate date, String orderNumbers,
                        List<PrescriptionService.DigitalLine> lines, String advice) {}

    public byte[] render(Sheet sheet) {
        Context ctx = new Context();
        ctx.setVariable("rx", sheet);
        ctx.setVariable("logo", InvoicePdfService.LOGO);
        String html = templateEngine.process("pdf/prescription", ctx);
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024)) {
            PdfRendererBuilder pdf = new PdfRendererBuilder();
            pdf.useFont(() -> InvoicePdfService.font("Figtree-Regular.ttf"), "Figtree", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> InvoicePdfService.font("Figtree-SemiBold.ttf"), "Figtree", 600, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> InvoicePdfService.font("Figtree-Bold.ttf"), "Figtree", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> InvoicePdfService.font("Figtree-ExtraBold.ttf"), "Figtree", 800, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> InvoicePdfService.font("NotoSans-Regular.ttf"), "Noto Sans", 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> InvoicePdfService.font("NotoSans-Bold.ttf"), "Noto Sans", 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.withHtmlContent(html, null);
            pdf.toStream(out);
            pdf.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Couldn't make the prescription PDF", e);
        }
    }
}
