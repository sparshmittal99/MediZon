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
 * master catalogue: a row with a known product code, or the exact name of one product, is that product; any other row
 * adds a product, which gets its code automatically. A row that would change a product already there is held for the
 * admin to review: each value it changes, now and in the file, to take or keep ({@link #applyReviews}). A category
 * that isn't in the catalogue yet is added with the product. A store imports its listings: each row names a master
 * product by code or name, with the store's stock, batch expiry and OTC price. Empty cells leave what's there. Good
 * rows are saved and every row that isn't is listed with the reason, so the sheet can be fixed and imported again.
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
            new Column("category", "Category", true, "e.g. Pain & fever; a new one is added to the categories", "group", "therapeuticclass"),
            new Column("form", "Form", false, "Tablet, Capsule, Syrup, Gel, ...; from the name, else Tablet, if empty", "dosageform", "type"),
            new Column("strength", "Strength", false, "e.g. 650 mg"),
            new Column("units", "Units per pack", false, "Tablets in one pack, or the quantity with its unit (100 ml, 30 g); 10 if empty",
                    "packsize", "unitsperpack", "unitspack", "unitsinpack", "units", "qtyperpack", "pack", "packing"),
            new Column("price", "Price", true, "Catalogue price in rupees, GST included", "catalogueprice", "sellingprice", "saleprice", "rate"),
            new Column("mrp", "MRP", false, "At least the price", "maxretailprice"),
            new Column("rx", "Prescription required", false, "Yes or No; No if empty", "prescription", "rx", "rxrequired", "rxreq", "rxreqd"),
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

    /** One value a row would change on a product already in the catalogue: what it is now, and what the file says. */
    public record Change(String key, String label, String current, String incoming) implements Serializable { }

    /** A product already in the catalogue that a row would change, held until the admin says which changes to make. */
    public record Review(Row row, Long medicineId, String code, String name, List<Change> changes) implements Serializable {
        public boolean touches(String key) { return changes.stream().anyMatch(c -> c.key().equals(key)); }
    }

    /** What an import did: shown on the catalogue page afterwards, and the products waiting for review. */
    public record Report(String file, int added, int updated, int unchanged, List<Skipped> skipped, List<Review> reviews)
            implements Serializable {
        public Report(String file, int added, int updated, List<Skipped> skipped) {
            this(file, added, updated, 0, skipped, List.of());
        }

        public String getSummary() {
            List<String> parts = new ArrayList<>();
            if (added > 0) parts.add(added + (added == 1 ? " product added" : " products added"));
            if (updated > 0) parts.add(updated + (updated == 1 ? " product updated" : " products updated"));
            if (unchanged > 0) parts.add(unchanged + (unchanged == 1 ? " product unchanged" : " products unchanged"));
            if (!reviews.isEmpty()) parts.add(reviews.size() + (reviews.size() == 1 ? " product to review" : " products to review"));
            if (!skipped.isEmpty()) parts.add(skipped.size() + (skipped.size() == 1 ? " row skipped" : " rows skipped"));
            return file + ": " + (parts.isEmpty() ? "nothing to import" : String.join(", ", parts)) + ".";
        }
    }

    private static final Set<String> YES = Set.of("yes", "y", "true", "1", "rx", "required");
    private static final Set<String> NO = Set.of("no", "n", "false", "0", "otc", "notrequired", "-");

    /** The columns a review compares, in the order it shows them; the product code never changes. */
    private static final List<String> REVIEWED = MASTER.stream().map(Column::key).filter(k -> !k.equals("code")).toList();

    private final MedicineRepository medicines;
    private final MedicineService medicineService;
    private final StoreCatalogService storeCatalog;
    private final Validator validator;

    // ------------------------------------------------------------------ master catalogue (admin)

    /**
     * Adds the file's new products. A row for a product already there that says the same as the catalogue is left
     * alone; one that would change it comes back in {@link Report#reviews()} for the admin to decide.
     */
    public Report importMaster(MultipartFile file) {
        List<Row> rows = SheetReader.read(file, MASTER);
        Map<String, Medicine> byCode = new HashMap<>();
        Map<String, List<Medicine>> byName = new HashMap<>();
        medicines.findAll().forEach(m -> remember(m, byCode, byName));
        List<String> categories = new ArrayList<>(medicineService.categories());
        int added = 0, unchanged = 0;
        List<Skipped> skipped = new ArrayList<>();
        List<Review> reviews = new ArrayList<>();
        for (Row row : rows) {
            String label = Optional.ofNullable(row.get("name")).orElse(Optional.ofNullable(row.get("code")).orElse("-"));
            try {
                Medicine existing = find(row, byCode, byName);
                // Changes go on a copy: the product stays exactly as it is until they're saved
                Medicine m = new Medicine();
                if (existing != null) BeanUtils.copyProperties(existing, m);
                applyMaster(row, m, existing == null, categories);
                if (existing == null) {
                    Medicine saved = medicineService.save(m, null, null, false);
                    remember(saved, byCode, byName);
                    if (categories.stream().noneMatch(c -> c.equalsIgnoreCase(saved.getCategory()))) categories.add(saved.getCategory());
                    added++;
                    continue;
                }
                List<Change> changes = changes(existing, m);
                if (changes.isEmpty()) unchanged++;
                else reviews.add(new Review(row, existing.getId(), existing.getProductCode(), existing.getName(), changes));
            } catch (BusinessException e) {
                skipped.add(new Skipped(row.number(), label, e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Catalogue import row {} failed", row.number(), e);
                skipped.add(new Skipped(row.number(), label, "This row couldn't be saved. Check its values and try again."));
            }
        }
        return new Report(file.getOriginalFilename(), added, 0, unchanged, skipped, reviews);
    }

    /**
     * Makes the changes the admin chose after an import: {@code accepted} holds, by each review's place in the list,
     * the columns to take from the file. Everything else stays as it is in the catalogue, and with it in every store.
     */
    public Report applyReviews(String file, List<Review> reviews, Map<Integer, Set<String>> accepted) {
        List<String> categories = new ArrayList<>(medicineService.categories());
        int updated = 0, kept = 0;
        List<Skipped> skipped = new ArrayList<>();
        for (int k = 0; k < reviews.size(); k++) {
            Review review = reviews.get(k);
            Set<String> take = new HashSet<>(accepted.getOrDefault(k, Set.of()));
            take.removeIf(key -> !review.touches(key));
            if (take.isEmpty()) {
                kept++;
                continue;
            }
            try {
                Medicine existing = medicines.findById(review.medicineId())
                        .orElseThrow(() -> new BusinessException("This product isn't in the catalogue any more."));
                Map<String, String> cells = new LinkedHashMap<>();
                take.forEach(key -> cells.put(key, review.row().cells().get(key)));
                Medicine m = new Medicine();
                BeanUtils.copyProperties(existing, m);
                applyMaster(new Row(review.row().number(), cells), m, false, categories);
                Medicine saved = medicineService.save(m, null, null, false);
                if (categories.stream().noneMatch(c -> c.equalsIgnoreCase(saved.getCategory()))) categories.add(saved.getCategory());
                updated++;
            } catch (BusinessException e) {
                skipped.add(new Skipped(review.row().number(), review.name(), e.getMessage()));
            } catch (RuntimeException e) {
                log.warn("Catalogue import review of row {} failed", review.row().number(), e);
                skipped.add(new Skipped(review.row().number(), review.name(), "This product couldn't be saved. Check its values and try again."));
            }
        }
        return new Report(file, 0, updated, kept, skipped, List.of());
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

    private void applyMaster(Row row, Medicine m, boolean isNew, Collection<String> categories) {
        if (isNew) {
            for (String key : List.of("name", "category", "price")) {
                if (row.get(key) == null) throw new BusinessException("Fill in the " + label(MASTER, key) + " for a new product.");
            }
        }
        // A name that differs only in case or spacing is the same product: it keeps the catalogue's spelling
        if (row.get("name") != null && !key(row.get("name")).equals(key(m.getName()))) m.setName(row.get("name"));
        set(row, "generic", m::setGenericName);
        set(row, "manufacturer", m::setManufacturer);
        set(row, "category", v -> m.setCategory(MedicineService.canonicalCategory(v, categories)));
        set(row, "form", m::setDosageForm);
        set(row, "strength", m::setStrength);
        set(row, "units", m::setUnitsPerPack);
        set(row, "description", v -> m.setDescription(v.length() > 2000 ? v.substring(0, 2000) : v));
        if (row.get("price") != null) m.setPrice(money(row.get("price"), "Price"));
        if (row.get("mrp") != null) m.setMrp(money(row.get("mrp"), "MRP"));
        if (row.get("rx") != null) m.setPrescriptionRequired(yesNo(row.get("rx"), "Prescription required"));
        if (row.get("returnable") != null) m.setReturnable(yesNo(row.get("returnable"), "Returnable"));
        if (row.get("hsn") != null) m.setHsnCode(row.get("hsn").replaceAll("\\.0+$", "").replaceAll("\\s", ""));
        if (row.get("gst") != null) m.setGstRate(money(row.get("gst").replace("%", ""), "GST %"));
        if (isNew && row.get("form") == null) m.setDosageForm(formFromName(m.getName()));
        // A syrup's or gel's "strength" is often what the pack holds (100 ml, 30 g)
        if (isNew && row.get("units") == null && m.getStrength() != null && PACK_QUANTITY.matcher(m.getStrength()).matches()) {
            m.setUnitsPerPack(m.getStrength());
        }
        Set<ConstraintViolation<Medicine>> problems = validator.validate(m);
        if (!problems.isEmpty()) throw new BusinessException(problems.iterator().next().getMessage() + ".");
        if (m.exceedsMrp(m.getPrice())) throw new BusinessException("The price can't be above the MRP.");
    }

    private static final java.util.regex.Pattern PACK_QUANTITY =
            java.util.regex.Pattern.compile("(?i)\\d+(\\.\\d+)?\\s*(ml|l|g|gm|kg)");

    /** Forms a product's name often says ("Volini Gel", "Ascoril LS Syrup"), first match wins; Tablet otherwise. */
    private static final List<String[]> FORMS = List.of(
            new String[]{"syrup", "Syrup"}, new String[]{"suspension", "Syrup"}, new String[]{"gel", "Gel"},
            new String[]{"ointment", "Ointment"}, new String[]{"cream", "Cream"}, new String[]{"lotion", "Lotion"},
            new String[]{"drops?", "Drops"}, new String[]{"injection|inj", "Injection"}, new String[]{"spray", "Spray"},
            new String[]{"capsules?|caps?", "Capsule"}, new String[]{"sachet", "Sachet"}, new String[]{"powder", "Powder"});

    static String formFromName(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (String[] f : FORMS) {
            if (java.util.regex.Pattern.compile("\\b(" + f[0] + ")\\b").matcher(n).find()) return f[1];
        }
        return "Tablet";
    }

    /** What applying the row changed on the copy {@code m} of {@code existing}, column by column. */
    private static List<Change> changes(Medicine existing, Medicine m) {
        List<Change> out = new ArrayList<>();
        for (String key : REVIEWED) {
            Object now = value(existing, key), next = value(m, key);
            if (!same(key, now, next)) out.add(new Change(key, label(MASTER, key), shown(now), shown(next)));
        }
        return out;
    }

    private static Object value(Medicine m, String key) {
        return switch (key) {
            case "name" -> m.getName();
            case "generic" -> m.getGenericName();
            case "manufacturer" -> m.getManufacturer();
            case "category" -> m.getCategory();
            case "form" -> m.getDosageForm();
            case "strength" -> m.getStrength();
            case "units" -> m.getUnitsPerPack();
            case "price" -> m.getPrice();
            case "mrp" -> m.getMrp();
            case "rx" -> m.isPrescriptionRequired();
            case "hsn" -> m.getHsnCode();
            case "gst" -> m.getGstRate();
            case "returnable" -> m.isReturnable();
            case "description" -> m.getDescription();
            default -> throw new IllegalArgumentException(key);
        };
    }

    private static boolean same(String key, Object a, Object b) {
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y) == 0;
        if (a instanceof String || b instanceof String || a == null || b == null) {
            String x = a == null ? "" : a.toString().trim().replaceAll("\\s+", " ");
            String y = b == null ? "" : b.toString().trim().replaceAll("\\s+", " ");
            return key.equals("category") ? x.equalsIgnoreCase(y) : x.equals(y);
        }
        return a.equals(b);
    }

    private static String shown(Object v) {
        if (v instanceof Boolean b) return b ? "Yes" : "No";
        if (v instanceof BigDecimal n) return n.stripTrailingZeros().toPlainString();
        return v == null || v.toString().isBlank() ? null : v.toString();
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
