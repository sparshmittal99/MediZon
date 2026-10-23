package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import com.medizon.repository.MedicineRepository;
import jakarta.validation.Validation;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CatalogImportTest {

    private MedicineRepository medicines;
    private MedicineService medicineService;
    private StoreCatalogService storeCatalog;
    private CatalogImportService importer;
    private final List<Medicine> saved = new ArrayList<>();
    private Medicine dolo;

    @BeforeEach
    void setUp() {
        medicines = mock(MedicineRepository.class);
        medicineService = mock(MedicineService.class);
        storeCatalog = mock(StoreCatalogService.class);
        importer = new CatalogImportService(medicines, medicineService, storeCatalog,
                Validation.buildDefaultValidatorFactory().getValidator());
        dolo = new Medicine();
        dolo.setId(7L);
        dolo.setProductCode("MZP00007");
        dolo.setName("Dolo 650");
        dolo.setCategory("Pain & fever");
        dolo.setPrice(new BigDecimal("30"));
        dolo.setMrp(new BigDecimal("33"));
        when(medicines.findAll()).thenReturn(List.of(dolo));
        when(medicineService.save(any(), any(), any(), anyBoolean())).thenAnswer(i -> {
            Medicine m = i.getArgument(0);
            if (m.getId() == null) m.setId(100L + saved.size());
            saved.add(m);
            return m;
        });
    }

    private static MockMultipartFile csv(String name, String content) {
        return new MockMultipartFile("file", name, "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void masterRowsAddNewProductsAndHoldChangesToKnownOnesForReview() {
        CatalogImportService.Report r = importer.importMaster(csv("products.csv", """
                Medicine Name,Category,Price (Rs),MRP,Units per pack,Prescription Required,GST %
                Crocin Advance,Pain & fever,"₹1,020.50",1100,15,No,12%
                dolo 650,,32,,,,
                ,Allergy,10,,,,
                Azee 500,Antibiotics,abc,,,yes,
                """));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.updated()).isZero();
        assertThat(r.skipped()).extracting(CatalogImportService.Skipped::row).containsExactly(4, 5);
        assertThat(r.skipped().get(1).reason()).contains("Price should be a number");

        Medicine crocin = saved.get(0);
        assertThat(crocin.getName()).isEqualTo("Crocin Advance");
        assertThat(crocin.getPrice()).isEqualByComparingTo("1020.50");
        assertThat(crocin.getUnitsPerPack()).isEqualTo("15");
        assertThat(crocin.getGstRate()).isEqualByComparingTo("12");
        assertThat(crocin.isPrescriptionRequired()).isFalse();
        // Dolo matched by name (any case): nothing saved yet, only its price would change
        assertThat(saved).hasSize(1);
        assertThat(r.reviews()).singleElement().satisfies(review -> {
            assertThat(review.medicineId()).isEqualTo(7L);
            assertThat(review.changes()).singleElement().satisfies(c -> {
                assertThat(c.key()).isEqualTo("price");
                assertThat(c.current()).isEqualTo("30");
                assertThat(c.incoming()).isEqualTo("32");
            });
        });

        when(medicines.findById(7L)).thenReturn(Optional.of(dolo));
        CatalogImportService.Report applied = importer.applyReviews("products.csv", r.reviews(), Map.of(0, Set.of("price")));
        assertThat(applied.updated()).isEqualTo(1);
        Medicine doloUpdate = saved.get(1);
        assertThat(doloUpdate.getId()).isEqualTo(7L);
        assertThat(doloUpdate.getPrice()).isEqualByComparingTo("32");
        assertThat(doloUpdate.getCategory()).isEqualTo("Pain & fever");
        assertThat(doloUpdate.getMrp()).isEqualByComparingTo("33");
        assertThat(dolo.getPrice()).isEqualByComparingTo("30");   // changes go on a copy until saved
    }

    @Test
    void aRowThatSaysWhatTheCatalogueSaysChangesNothing() {
        CatalogImportService.Report r = importer.importMaster(csv("same.csv", """
                Name,Category,Price,MRP
                DOLO 650,pain & FEVER,30.00,33
                """));
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(r.reviews()).isEmpty();
        assertThat(saved).isEmpty();
    }

    @Test
    void onlyTheChangesTheAdminTakesAreMade() {
        when(medicines.findById(7L)).thenReturn(Optional.of(dolo));
        when(medicineService.categories()).thenReturn(List.of("Pain & fever"));
        CatalogImportService.Report r = importer.importMaster(csv("changes.csv", """
                Name,Category,Price,MRP,Manufacturer
                Dolo 650,Analgesics,31,33,Micro Labs
                """));
        assertThat(r.reviews()).singleElement().satisfies(review ->
                assertThat(review.changes()).extracting(CatalogImportService.Change::key).containsExactly("manufacturer", "category", "price"));

        // Keeping every value saves nothing
        CatalogImportService.Report kept = importer.applyReviews("changes.csv", r.reviews(), Map.of());
        assertThat(kept.unchanged()).isEqualTo(1);
        assertThat(saved).isEmpty();

        // The new category is taken, the price kept
        importer.applyReviews("changes.csv", r.reviews(), Map.of(0, Set.of("category", "manufacturer")));
        assertThat(saved).singleElement().satisfies(m -> {
            assertThat(m.getCategory()).isEqualTo("Analgesics");
            assertThat(m.getManufacturer()).isEqualTo("Micro Labs");
            assertThat(m.getPrice()).isEqualByComparingTo("30");
        });
    }

    @Test
    void newProductsTakeTheCatalogueSpellingOfACategoryAndAFormFromTheirName() {
        when(medicineService.categories()).thenReturn(List.of("Cough & Cold"));
        importer.importMaster(csv("new.csv", """
                Name,Category,Strength,Price,Units per pack
                Ascoril LS Syrup,cough & cold,100 ml,102,
                Volini Gel,Topical Pain Relief,30 g,82,
                Benadryl,Cough & Cold,,90,150 ml
                """));
        assertThat(saved).extracting(Medicine::getCategory).containsExactly("Cough & Cold", "Topical Pain Relief", "Cough & Cold");
        assertThat(saved).extracting(Medicine::getDosageForm).containsExactly("Syrup", "Gel", "Tablet");
        assertThat(saved).extracting(Medicine::getUnitsPerPack).containsExactly("100 ml", "30 g", "150 ml");
    }

    @Test
    void storeRowsListByCodeOrNameAndKeepWhatEmptyCellsDontSay() {
        Store store = new Store();
        StoreItem listed = new StoreItem();
        listed.setMedicine(dolo);
        listed.setStock(5);
        listed.setExpiryDate(LocalDate.of(2027, 1, 31));
        when(storeCatalog.find(store, dolo)).thenReturn(Optional.of(listed));

        CatalogImportService.Report r = importer.importStore(store, csv("stock.csv", """
                Item Code,Product,Qty,Exp,Rate
                MZP00007,,40,05/2027,
                ,DOLO 650,,,31
                ,Unknown Syrup,3,,
                """));
        assertThat(r.updated()).isEqualTo(2);
        assertThat(r.skipped()).singleElement().satisfies(s -> assertThat(s.reason()).contains("isn't in the master catalogue"));
        verify(storeCatalog).list(store, 7L, null, 40, LocalDate.of(2027, 5, 31));
        verify(storeCatalog).list(store, 7L, new BigDecimal("31"), 5, LocalDate.of(2027, 1, 31));
    }

    @Test
    void excelSheetsAreReadWithATitleAboveTheTable() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet s = wb.createSheet();
            s.createRow(0).createCell(0).setCellValue("Stock statement, October");
            Row head = s.createRow(2);
            head.createCell(0).setCellValue("Product code");
            head.createCell(1).setCellValue("Stock (packs)");
            head.createCell(2).setCellValue("Batch expiry");
            Row row = s.createRow(3);
            row.createCell(0).setCellValue("MZP00007");
            row.createCell(1).setCellValue(12);
            row.createCell(2).setCellValue("Mar 2028");
            wb.write(out);
        }
        List<SheetReader.Row> rows = SheetReader.read(new MockMultipartFile("file", "s.xlsx", null, out.toByteArray()), CatalogImportService.STORE);
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r.number()).isEqualTo(4);
            assertThat(r.get("code")).isEqualTo("MZP00007");
            assertThat(r.get("stock")).isEqualTo("12");
            assertThat(CatalogImportService.expiry(r.get("expiry"))).isEqualTo(LocalDate.of(2028, 3, 31));
        });
    }

    @Test
    void pdfTablesAreReadFromWhereTheTextSits() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            String[][] lines = {
                    {"Name", "Category", "Price", "MRP"},
                    {"Cetirizine 10 mg", "Allergy", "18.50", "20"},
                    {"Pan 40", "Stomach care", "112", ""}};
            float[] xs = {50, 220, 360, 450};
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                for (int l = 0; l < lines.length; l++) {
                    for (int c = 0; c < xs.length; c++) {
                        if (lines[l][c].isEmpty()) continue;
                        cs.beginText();
                        cs.setFont(font, 11);
                        cs.newLineAtOffset(xs[c], 700 - l * 20);
                        cs.showText(lines[l][c]);
                        cs.endText();
                    }
                }
            }
            doc.save(out);
        }
        List<SheetReader.Row> rows = SheetReader.read(new MockMultipartFile("file", "list.pdf", "application/pdf", out.toByteArray()),
                CatalogImportService.MASTER);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("name")).isEqualTo("Cetirizine 10 mg");
        assertThat(rows.get(0).get("category")).isEqualTo("Allergy");
        assertThat(rows.get(0).get("price")).isEqualTo("18.50");
        assertThat(rows.get(1).get("category")).isEqualTo("Stomach care");
        assertThat(rows.get(1).get("mrp")).isNull();
    }

    /**
     * A table like a generated price list: small type, headings and short values centred on each row, longer values
     * wrapped onto lines above and below them, and (when {@code ruled}) a line drawn between rows.
     */
    private static byte[] wrappedTable(boolean ruled) throws Exception { return wrappedTable(ruled, false); }

    /** {@code printed}: as a browser prints a page, with a page-sized background and a footer under a line. */
    private static byte[] wrappedTable(boolean ruled, boolean printed) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(new org.apache.pdfbox.pdmodel.common.PDRectangle(842, 595));
            doc.addPage(page);
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (printed) {
                    cs.setNonStrokingColor(java.awt.Color.WHITE);
                    cs.addRect(0, 0, 842, 595);
                    cs.fill();
                    cs.setNonStrokingColor(java.awt.Color.BLACK);
                    cs.moveTo(40, 60);
                    cs.lineTo(800, 60);
                    cs.stroke();
                    cs.beginText();
                    cs.setFont(font, 6);
                    cs.newLineAtOffset(740, 45);
                    cs.showText("Page 1 of 2");
                    cs.endText();
                }
                // Each text: x, offset from the row's middle line (in lines), text
                Object[][][] rows = {
                        {{40f, 0f, "Medicine Name"}, {150f, 0f, "Composition"}, {300f, 0f, "Category"}, {400f, -0.5f, "Price"},
                                {398f, 0.5f, "(Rs.)"}, {450f, -0.5f, "Rx"}, {446f, 0.5f, "Req."}, {520f, 0f, "Description"}},
                        {{30f, 0f, "Meftal-Spas"}, {120f, -1f, "Mefenamic Acid 250 mg +"}, {120f, 1f, "Dicyclomine 10 mg"},
                                {290f, 0f, "Pain & Fever"}, {400f, 0f, "42.00"}, {450f, 0f, "Yes"},
                                {500f, -1f, "Relief from abdominal cramps and menstrual"}, {500f, 1f, "pain."}},
                        {{30f, 0f, "Ecosprin 75"}, {120f, 0f, "Aspirin 75 mg"}, {290f, -1f, "Cardiac & Blood"}, {290f, 1f, "Pressure"},
                                {400f, 0f, "4.50"}, {452f, 0f, "No"}, {500f, 0f, "Low-dose antiplatelet."}}};
                float rowTop = 520, rowHeight = 24, line = 4;
                for (int r = 0; r < rows.length; r++) {
                    float middle = rowTop - r * rowHeight - rowHeight / 2;
                    for (Object[] t : rows[r]) {
                        cs.beginText();
                        cs.setFont(font, 6);
                        cs.newLineAtOffset((Float) t[0], middle - (Float) t[1] * line);
                        cs.showText((String) t[2]);
                        cs.endText();
                    }
                    if (ruled) {
                        cs.moveTo(25, rowTop - (r + 1) * rowHeight);
                        cs.lineTo(815, rowTop - (r + 1) * rowHeight);
                        cs.stroke();
                    }
                }
                if (ruled) {
                    cs.moveTo(25, rowTop);
                    cs.lineTo(815, rowTop);
                    cs.stroke();
                }
            }
            doc.save(out);
        }
        return out.toByteArray();
    }

    @Test
    void pdfCellsThatWrapOntoSeveralLinesStayOneCell() throws Exception {
        for (boolean ruled : new boolean[]{true, false}) {
            List<SheetReader.Row> rows = SheetReader.read(new MockMultipartFile("file", "list.pdf", "application/pdf", wrappedTable(ruled)),
                    CatalogImportService.MASTER);
            assertThat(rows).as("ruled: %s", ruled).hasSize(2);
            SheetReader.Row meftal = rows.get(0), ecosprin = rows.get(1);
            assertThat(meftal.get("name")).isEqualTo("Meftal-Spas");
            assertThat(meftal.get("generic")).isEqualTo("Mefenamic Acid 250 mg + Dicyclomine 10 mg");
            assertThat(meftal.get("category")).isEqualTo("Pain & Fever");
            assertThat(meftal.get("price")).isEqualTo("42.00");
            assertThat(meftal.get("rx")).isEqualTo("Yes");
            assertThat(meftal.get("description")).isEqualTo("Relief from abdominal cramps and menstrual pain.");
            assertThat(ecosprin.get("name")).isEqualTo("Ecosprin 75");
            assertThat(ecosprin.get("category")).isEqualTo("Cardiac & Blood Pressure");
            assertThat(ecosprin.get("rx")).isEqualTo("No");
        }
    }

    @Test
    void aPrintedPagesBackgroundAndFooterAreNotRows() throws Exception {
        List<SheetReader.Row> rows = SheetReader.read(new MockMultipartFile("file", "list.pdf", "application/pdf", wrappedTable(true, true)),
                CatalogImportService.MASTER);
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Meftal-Spas", "Ecosprin 75");
        assertThat(rows.get(1).get("category")).isEqualTo("Cardiac & Blood Pressure");
    }

    @Test
    void cellValuesInTheUsualShapes() {
        assertThat(CatalogImportService.money("Rs. 1,200/-", "Price")).isEqualByComparingTo("1200");
        assertThat(CatalogImportService.yesNo("Rx", "Prescription")).isTrue();
        assertThat(CatalogImportService.yesNo("OTC", "Prescription")).isFalse();
        assertThat(CatalogImportService.expiry("31/05/2027")).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(CatalogImportService.expiry("2027-05-31")).isEqualTo(LocalDate.of(2027, 5, 31));
        assertThat(CatalogImportService.expiry("FEB-28")).isEqualTo(LocalDate.of(2028, 2, 29));
        assertThatThrownBy(() -> CatalogImportService.wholeNumber("2.5", "Stock")).hasMessageContaining("whole number");
        assertThatThrownBy(() -> SheetReader.read(csv("x.csv", "foo,bar\n1,2\n"), CatalogImportService.MASTER))
                .hasMessageContaining("headings weren't found");
    }
}
