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
    void masterRowsAddNewProductsAndUpdateKnownOnes() {
        CatalogImportService.Report r = importer.importMaster(csv("products.csv", """
                Medicine Name,Category,Price (Rs),MRP,Units per pack,Prescription Required,GST %
                Crocin Advance,Pain & fever,"₹1,020.50",1100,15,No,12%
                dolo 650,,32,,,,
                ,Allergy,10,,,,
                Azee 500,Antibiotics,abc,,,yes,
                """));
        assertThat(r.added()).isEqualTo(1);
        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.skipped()).extracting(CatalogImportService.Skipped::row).containsExactly(4, 5);
        assertThat(r.skipped().get(1).reason()).contains("Price should be a number");

        Medicine crocin = saved.get(0);
        assertThat(crocin.getName()).isEqualTo("Crocin Advance");
        assertThat(crocin.getPrice()).isEqualByComparingTo("1020.50");
        assertThat(crocin.getUnitsPerPack()).isEqualTo(15);
        assertThat(crocin.getGstRate()).isEqualByComparingTo("12");
        assertThat(crocin.isPrescriptionRequired()).isFalse();
        // Dolo matched by name (any case): price changed, everything else kept
        Medicine doloUpdate = saved.get(1);
        assertThat(doloUpdate.getId()).isEqualTo(7L);
        assertThat(doloUpdate.getPrice()).isEqualByComparingTo("32");
        assertThat(doloUpdate.getCategory()).isEqualTo("Pain & fever");
        assertThat(doloUpdate.getMrp()).isEqualByComparingTo("33");
        assertThat(dolo.getPrice()).isEqualByComparingTo("30");   // changes go on a copy until saved
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
