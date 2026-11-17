package com.medizon.service;

import com.medizon.domain.Medicine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The digital prescription a pharmacist issues: a real PDF with the patient, each medicine and how to take it. */
class PrescriptionPdfServiceTest {

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

    @Test
    void rendersThePatientMedicinesAndDoses() throws Exception {
        Medicine acitrom = new Medicine();
        acitrom.setName("Acitrom 3");
        byte[] pdf = new PrescriptionPdfService(engine()).render(new PrescriptionPdfService.Sheet("Asha Sharma", "Sparsh Mittal",
                "Priya Shah", LocalDate.of(2026, 11, 17), "MZ26111700001",
                List.of(new PrescriptionService.DigitalLine(acitrom, 2, "1-0-1", "After food · 5 days")), "Drink plenty of water."));

        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Asha Sharma", "Ordered by Sparsh Mittal", "MZ26111700001", "Acitrom 3", "1-0-1",
                    "After food · 5 days", "Drink plenty of water.", "Priya Shah", "17 Nov 2026");
        }
    }
}
