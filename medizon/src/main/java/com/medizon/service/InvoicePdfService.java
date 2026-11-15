package com.medizon.service;

import com.medizon.domain.Order;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Base64;

/**
 * The tax invoice as a PDF file, for emailing: templates/pdf/invoice.html rendered by Thymeleaf, then turned into a
 * PDF with OpenHTMLtoPDF. It is made to look like the invoice page on the website (orders/invoice.html): the same
 * Figtree font, embedded so the rupee sign prints the same everywhere (Noto Sans covers any letter Figtree lacks),
 * and the header's brand mark, a white capsule on a green disc.
 * Call it where the order's lazy data can still be read (a web request).
 */
@Service
@RequiredArgsConstructor
public class InvoicePdfService {

    private static final String FONT = "Figtree";
    private static final String FALLBACK_FONT = "Noto Sans";
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
            pdf.useFont(() -> font("Figtree-Regular.ttf"), FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("Figtree-Medium.ttf"), FONT, 500, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("Figtree-SemiBold.ttf"), FONT, 600, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("Figtree-Bold.ttf"), FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("Figtree-ExtraBold.ttf"), FONT, 800, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("NotoSans-Regular.ttf"), FALLBACK_FONT, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
            pdf.useFont(() -> font("NotoSans-Bold.ttf"), FALLBACK_FONT, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
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

    /**
     * The website header's brand mark (.mz-mark: Bootstrap's bi-capsule, white, turned -35 degrees on a green disc),
     * drawn as a PNG because the PDF renderer can't draw icon fonts or SVG.
     */
    private static String logoDataUri() {
        try {
            int size = 192;
            double unit = size / 32.0;   // the disc is 32 px across and the icon 16 px, as on the website
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setColor(new Color(0x0E6B57));
            g.fill(new Ellipse2D.Double(0, 0, size, size));

            // The capsule along the x axis in icon units: 18 long and 8 thick; one half solid, the other an outline
            Area capsule = new Area(new RoundRectangle2D.Double(-9, -4, 18, 8, 8, 8));
            Area hollow = new Area(new RoundRectangle2D.Double(-8, -3, 16, 6, 6, 6));
            hollow.intersect(new Area(new Rectangle2D.Double(0.9, -4, 10, 8)));
            capsule.subtract(hollow);

            AffineTransform at = new AffineTransform();
            at.translate(size / 2.0, size / 2.0);
            at.scale(unit, unit);
            at.rotate(Math.toRadians(-80));   // bi-capsule points up-right at 45 degrees, then 35 more
            g.setColor(Color.WHITE);
            g.fill(capsule.createTransformedArea(at));
            g.dispose();

            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(img, "png", png);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }
}
