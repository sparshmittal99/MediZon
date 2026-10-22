package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import com.medizon.repository.MedicineRepository;
import com.medizon.service.SheetReader.Column;
import com.medizon.service.SheetReader.Row;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Catalogue imports from a sheet (Excel, CSV or PDF; see {@link SheetReader}). The admin imports products into the
 * master catalogue: a row with a known product code, or the exact name of one product, updates it; any other row adds
 * a product, which gets its code automatically. A store imports its listings: each row names a master product by code
 * or name, with the store's stock, batch expiry and OTC price. Empty cells leave what's there. Good rows are saved and
 * every row that isn't is listed with the reason, so the sheet can be fixed and imported again.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogImportService {

    public static final List<Column> MASTER = List.of(
            new Column("code", "Product code", false, "Only to update a product already in the catalogue; new products get one automatically",
                    "code", "productcode", "sku", "itemcode"),
            new Column("name", "Name", true, "e.g. Dolo 650", "productname", "medicinename", "medicine", "product", "itemname", "brandname"),
            new Column("generic", "Salt / composition", false, "e.g. Paracetamol 650 mg", "salt", "composition", "genericname", "generic"),
            new Column("manufacturer", "Manufacturer", false, "", "company", "mfr", "mfg", "marketedby", "marketer"),
            new Column("category", "Category", true, "e.g. Pain & fever", "group", "therapeuticclass"),
            new Column("form", "Form", false, "Tablet, Capsule, Syrup, ...; Tablet if empty", "dosageform", "type"),
            new Column("strength", "Strength", false, "e.g. 650 mg"),
            new Column("units", "Units per pack", false, "Tablets or ml in one pack; 10 if empty", "packsize", "unitsperpack", "units", "qtyperpack", "pack"),
            new Column("price", "Price", true, "Catalogue price in rupees, GST included", "catalogueprice", "sellingprice", "saleprice", "rate"),
            new Column("mrp", "MRP", false, "At least the price", "maxretailprice"),
            new Column("rx", "Prescription required", false, "Yes or No; No if empty", "prescription", "rx", "rxrequired"),
            new Column("hsn", "HSN code", false, "4 to 8 digits; 3004 if empty", "hsn"),
            new Column("gst", "GST %", false, "0, 5, 12 or 18; 5 if empty", "gst", "gstrate", "tax", "taxrate"),
            new Column("returnable", "Returnable", false, "Yes or No; Yes if empty", "return"),
            new Column("description", "Description", false, "", "details", "notes"));

    public static final List<Column> STORE = List.of(
            new Column("code", "Product code", false, "Product code or name is needed", "code", "productcode", "sku", "itemcode"),
            new Column("name", "Name", false, "Exactly as in the master catalogue", "productname", "medicinename", "medicine", "product", "itemname", "brandname"),
            new Column("stock", "Stock (packs)", false, "Packs in the store; 0 for a new product if empty", "stock", "quantity", "qty", "packs", "available", "closingstock"),
            new Column("expiry", "Batch expiry", false, "e.g. 2027-05-31 or 05/2027", "expiry", "expirydate", "exp", "expdate"),
            new Column("price", "Price (OTC)", false, "Store price for OTC medicines; the catalogue price if empty",
                    "price", "storeprice", "sellingprice", "saleprice", "rate"));

    /** One row that wasn't imported, and why. */
    public record Skipped(int row, String product, String reason) implements Serializable { }

    /** What an import did: shown on the catalogue page afterwards. */
    public record Report(String file, int added, int updated, List<Skipped> skipped) implements Serializable {
        public String getSummary() {
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add(added + (added == 1 ? " product added" : " products added"));
            if (updated > 0) parts.add(updated + (updated == 1 ? " product updated" : " products updated"));
            if (!skipped.isEmpty()) parts.add(skipped.size() + (skipped.size() == 1 ? " row skipped" : " rows skipped"));
            return file + ": " + (parts.isEmpty() ? "nothing to import" : String.join(", ", parts)) + ".";
        }
    }

    private static final Set<String> YES = Set.of("yes", "y", "true", "1", "rx", "required");
    private static final Set<String> NO = Set.of("no", "n", "false", "0", "otc", "notrequired", "-");

    private final MedicineRepository medicines;
    private final MedicineService medicineService;
    private final StoreCatalogService storeCatalog;
    private final Validator validator;

    // ------------------------------------------------------------------ master catalogue (admin)

    public Report importMaster(MultipartFile file) {
        List<Row> rows = SheetReader.read(file, MASTER);
        Map<String, Medicine> byCode = new HashMap<>();
        Map<String, List<Medicine>> byName = new HashMap<>();
        medicines.findAll().forEach(m -> remember(m, byCode, byName));
        int added = 0, updated = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (Row row : rows) {
            String label = Optional.ofNullable(row.get("name")).orElse(Optional.ofNullable(row.get("code")).orElse("-"));
            try {
                Medicine existing = find(row, byCode, byName);
                // Changes go on a copy, so a row that fails leaves the product exactly as it was
                Medicine m = new Medicine();
                if (existing != null) BeanUtils.copyProperties(existing, m);
                applyMaster(row, m, existing == null);
                Medicine saved = medicineService.save(m, null, null, false);
                remember(saved, byCode, byName);
                if (existing == null) added++; else updated++;
            } catch (BusinessException e) {
                skipped.add(new Skipped(row.number(), label, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Catalogue import row {} failed", row.number(), e);
                skipped.add(new Skipped(row.number(), label, "This row couldn't be saved. Check its values and try again."));
            }
        }
        return new Report(file.getOriginalFilename(), added, updated, skipped);
    }

    private static void remember(Medicine m, Map<String, Medicine> byCode, Map<String, List<Medicine>> byName) {
        if (m.getProductCode() != null) byCode.put(m.getProductCode().toUpperCase(Locale.ROOT), m);
        List<Medicine> same = byName.computeIfAbsent(key(m.getName()), k -> new ArrayList<>());
        same.removeIf(x -> Objects.equals(x.getId(), m.getId()));
        same.add(m);
    }

    /** The product a row means: by product code, else by its exact name when exactly one product has it. */
    private static Medicine find(Row row, Map<String, Medicine> byCode, Map<String, List<Medicine>> byName) {
        String code = row.get("code");
        if (code != null) {
            Medicine m = byCode.get(code.toUpperCase(Locale.ROOT));
            if (m == null) throw new BusinessException("No product has the code " + code + ". Leave the code empty to add a new product.");
            return m;
        }
        String name = row.get("name");
        List<Medicine> same = name == null ? List.of() : byName.getOrDefault(key(name), List.of());
        if (same.size() > 1) throw new BusinessException(same.size() + " products are called " + name + ". Add the product code to say which one.");
        return same.isEmpty() ? null : same.get(0);
    }

    private void applyMaster(Row row, Medicine m, boolean isNew) {
        if (isNew) {
            for (String key : List.of("name", "category", "price")) {
                if (row.get(key) == null) throw new BusinessException("Fill in the " + label(MASTER, key) + " for a new product.");
            }
        }
        set(row, "name", m::setName);
        set(row, "generic", m::setGenericName);
        set(row, "manufacturer", m::setManufacturer);
        set(row, "category", m::setCategory);
        set(row, "form", m::setDosageForm);
        set(row, "strength", m::setStrength);
        set(row, "description", v -> m.setDescription(v.length() > 2000 ? v.substring(0, 2000) : v));
        if (row.get("units") != null) m.setUnitsPerPack(wholeNumber(row.get("units"), "Units per pack"));
        if (row.get("price") != null) m.setPrice(money(row.get("price"), "Price"));
        if (row.get("mrp") != null) m.setMrp(money(row.get("mrp"), "MRP"));
        if (row.get("rx") != null) m.setPrescriptionRequired(yesNo(row.get("rx"), "Prescription required"));
        if (row.get("returnable") != null) m.setReturnable(yesNo(row.get("returnable"), "Returnable"));
        if (row.get("hsn") != null) m.setHsnCode(row.get("hsn").replaceAll("\\.0+$", "").replaceAll("\\s", ""));
        if (row.get("gst") != null) m.setGstRate(money(row.get("gst").replace("%", ""), "GST %"));
        Set<ConstraintViolation<Medicine>> problems = validator.validate(m);
        if (!problems.isEmpty()) throw new BusinessException(problems.iterator().next().getMessage() + ".");
    }

    // ------------------------------------------------------------------ a store's catalogue

    public Report importStore(Store store, MultipartFile file) {
        List<Row> rows = SheetReader.read(file, STORE);
        List<Medicine> all = medicines.findAll();
        Map<String, Medicine> byCode = all.stream().filter(m -> m.getProductCode() != null)
                .collect(Collectors.toMap(m -> m.getProductCode().toUpperCase(Locale.ROOT), Function.identity()));
        Map<String, List<Medicine>> byName = all.stream().collect(Collectors.groupingBy(m -> key(m.getName())));
        int added = 0, updated = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (Row row : rows) {
            String code = row.get("code"), name = row.get("name");
            String label = name != null ? name : code != null ? code : "-";
            try {
                Medicine m = storeProduct(code, name, byCode, byName);
                Optional<StoreItem> listed = storeCatalog.find(store, m);
                // Empty cells keep what the store already has
                int stock = row.get("stock") != null ? wholeNumber(row.get("stock"), "Stock") : listed.map(StoreItem::getStock).orElse(0);
                LocalDate expiry = row.get("expiry") != null ? expiry(row.get("expiry")) : listed.map(StoreItem::getExpiryDate).orElse(null);
                BigDecimal price = row.get("price") != null ? money(row.get("price"), "Price") : listed.map(StoreItem::getPrice).orElse(null);
                storeCatalog.list(store, m.getId(), price, stock, expiry);
                if (listed.isPresent()) updated++; else added++;
            } catch (BusinessException e) {
                skipped.add(new Skipped(row.number(), label, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Catalogue import row {} failed", row.number(), e);
                skipped.add(new Skipped(row.number(), label, "This row couldn't be saved. Check its values and try again."));
            }
        }
        return new Report(file.getOriginalFilename(), added, updated, skipped);
    }

    private static Medicine storeProduct(String code, String name, Map<String, Medicine> byCode, Map<String, List<Medicine>> byName) {
        if (code == null && name == null) throw new BusinessException("Give the product code or name.");
        if (code != null) {
            Medicine m = byCode.get(code.toUpperCase(Locale.ROOT));
            if (m != null) return m;
            if (name == null) throw new BusinessException("No product has the code " + code + ".");
        }
        List<Medicine> same = byName.getOrDefault(key(name), List.of());
        if (same.isEmpty()) throw new BusinessException(name + " isn't in the master catalogue. Ask the admin to add it first.");
        if (same.size() > 1) throw new BusinessException(same.size() + " products are called " + name + ". Use the product code instead.");
        return same.get(0);
    }

    // ------------------------------------------------------------------ templates

    /**
     * The sheet to fill in: the columns, and the products already there so they can be changed and imported back
     * (every master product for the admin, the store's listings for a store). A second sheet explains each column.
     */
    public byte[] template(Store store) {
        List<Column> columns = store == null ? MASTER : STORE;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Products");
            CellStyle bold = wb.createCellStyle();
            Font font = wb.createFont();
            font.setBold(true);
            bold.setFont(font);
            org.apache.poi.ss.usermodel.Row head = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) {
                Cell cell = head.createCell(c);
                cell.setCellValue(columns.get(c).label() + (columns.get(c).required() ? " *" : ""));
                cell.setCellStyle(bold);
            }
            sheet.createFreezePane(0, 1);
            int r = 1;
            if (store == null) {
                for (Medicine m : medicineService.all()) fill(sheet.createRow(r++), masterValues(m));
            } else {
                for (StoreItem i : storeCatalog.items(store)) {
                    fill(sheet.createRow(r++), Arrays.asList(i.getMedicine().getProductCode(), i.getMedicine().getName(), i.getStock(),
                            i.getExpiryDate() == null ? null : i.getExpiryDate().toString(), i.getPrice()));
                }
            }
            for (int c = 0; c < columns.size(); c++) sheet.setColumnWidth(c, 18 * 256);

            Sheet help = wb.createSheet("How to fill");
            org.apache.poi.ss.usermodel.Row h = help.createRow(0);
            String[] titles = {"Column", "Needed", "What to put"};
            for (int c = 0; c < titles.length; c++) {
                h.createCell(c).setCellValue(titles[c]);
                h.getCell(c).setCellStyle(bold);
            }
            for (int k = 0; k < columns.size(); k++) {
                Column col = columns.get(k);
                fill(help.createRow(k + 1), Arrays.asList(col.label(), col.required() ? "Yes" : "No", col.help()));
            }
            help.createRow(columns.size() + 2).createCell(0).setCellValue(store == null
                    ? "A row with a product code, or the exact name of one product, updates it; other rows add new products. Empty cells keep what's there."
                    : "Each row is a master catalogue product, by code or exact name. Rows already in the store update it; empty cells keep what's there.");
            help.setColumnWidth(0, 22 * 256);
            help.setColumnWidth(2, 90 * 256);
            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new BusinessException("The template couldn't be created. Try again.");
        }
    }

    private static List<Object> masterValues(Medicine m) {
        return Arrays.asList(m.getProductCode(), m.getName(), m.getGenericName(), m.getManufacturer(), m.getCategory(),
                m.getDosageForm(), m.getStrength(), m.getUnitsPerPack(), m.getPrice(), m.getMrp(),
                m.isPrescriptionRequired() ? "Yes" : "No", m.getHsnCode(), m.getGstRate(), m.isReturnable() ? "Yes" : "No",
                m.getDescription());
    }

    private static void fill(org.apache.poi.ss.usermodel.Row row, List<?> values) {
        for (int c = 0; c < values.size(); c++) {
            Object v = values.get(c);
            if (v instanceof Number n) row.createCell(c).setCellValue(n.doubleValue());
            else if (v != null) row.createCell(c).setCellValue(v.toString());
        }
    }

    // ------------------------------------------------------------------ cell values

    private static String key(String name) { return name == null ? "" : name.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " "); }

    private static String label(List<Column> columns, String key) {
        return columns.stream().filter(c -> c.key().equals(key)).map(Column::label).findFirst().orElse(key);
    }

    private static void set(Row row, String key, java.util.function.Consumer<String> setter) {
        if (row.get(key) != null) setter.accept(row.get(key));
    }

    /** "₹1,234.50", "Rs. 20" and "20/-" are all amounts. */
    static BigDecimal money(String value, String column) {
        String v = value.replaceAll("(?i)rs\\.?|inr|₹|,|/-|\\s", "");
        try {
            BigDecimal n = new BigDecimal(v);
            if (n.signum() < 0) throw new BusinessException(column + " can't be negative.");
            return n;
        } catch (NumberFormatException e) {
            throw new BusinessException(column + " should be a number, not \"" + value + "\".");
        }
    }

    static int wholeNumber(String value, String column) {
        BigDecimal n = money(value, column);
        try {
            return n.stripTrailingZeros().intValueExact();
        } catch (ArithmeticException e) {
            throw new BusinessException(column + " should be a whole number, not \"" + value + "\".");
        }
    }

    static boolean yesNo(String value, String column) {
        String v = value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s", "");
        if (YES.contains(v)) return true;
        if (NO.contains(v)) return false;
        throw new BusinessException(column + " should be Yes or No, not \"" + value + "\".");
    }

    private static final List<DateTimeFormatter> DAYS = List.of(DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"), DateTimeFormatter.ofPattern("d.M.uuuu"),
            DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH));
    private static final List<DateTimeFormatter> MONTHS = List.of(DateTimeFormatter.ofPattern("M/uuuu"),
            DateTimeFormatter.ofPattern("M-uuuu"), DateTimeFormatter.ofPattern("uuuu-M"), DateTimeFormatter.ofPattern("MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("M/uu"), DateTimeFormatter.ofPattern("MMM-uu", Locale.ENGLISH));

    /** A day ("2027-05-31", "31/05/2027") or, as on most packs, a month ("05/2027", "May 2027"): the month's last day. */
    static LocalDate expiry(String value) {
        String v = value.trim();
        String cap = v.length() > 1 ? v.substring(0, 1).toUpperCase(Locale.ROOT) + v.substring(1).toLowerCase(Locale.ROOT) : v;
        for (DateTimeFormatter f : DAYS) {
            try { return LocalDate.parse(cap, f); } catch (DateTimeParseException ignored) { }
        }
        for (DateTimeFormatter f : MONTHS) {
            try { return YearMonth.parse(cap, f).atEndOfMonth(); } catch (DateTimeParseException ignored) { }
        }
        throw new BusinessException("Batch expiry should be a date such as 2027-05-31 or 05/2027, not \"" + value + "\".");
    }
}
