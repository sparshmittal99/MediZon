package com.medizon.config;

import com.medizon.domain.Coupon;
import com.medizon.domain.Medicine;
import com.medizon.domain.Role;
import com.medizon.domain.Store;
import com.medizon.domain.StoreItem;
import com.medizon.domain.User;
import com.medizon.repository.CouponRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StoreItemRepository;
import com.medizon.repository.StoreRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Startup housekeeping: keeps the one admin account in line with MEDIZON_ADMIN_EMAIL / MEDIZON_ADMIN_PASSWORD, moves a
 * database from before stores into a "main store", and seeds a starter catalog on an empty database.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final MedicineRepository medicines;
    private final StoreRepository stores;
    private final StoreItemRepository storeItems;
    private final CouponRepository coupons;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    @Value("${medizon.admin.email:}") private String adminEmail;
    @Value("${medizon.admin.password:}") private String adminPassword;
    @Value("${medizon.admin.name:}") private String adminName;

    @Value("${medizon.seller.name:MediZon Pharmacy}") private String sellerName;
    @Value("${medizon.seller.gstin:}") private String sellerGstin;
    @Value("${medizon.seller.drug-license:}") private String sellerDrugLicense;
    @Value("${medizon.seller.address:}") private String sellerAddress;
    @Value("${medizon.seller.state:}") private String sellerState;
    @Value("${medizon.seller.phone:}") private String sellerPhone;
    @Value("${medizon.seller.email:}") private String sellerEmail;

    @Override
    @Transactional
    public void run(String... args) {
        dropStaleEnumChecks();
        backfillInvoicePatients();
        relaxLegacyColumns();
        unitsPerPackAsText();
        if (stores.count() == 0) {
            Store main = createMainStore();
            if (medicines.count() > 0) moveIntoStore(main);
        }
        if (medicines.count() == 0) seedCatalog(stores.findAllByOrderByNameAsc().get(0));
        if (coupons.count() == 0) seedCoupons();
        syncAdmin();
    }

    /** Sample promos until the admin can manage their own. */
    private void seedCoupons() {
        coupons.saveAll(List.of(
                new Coupon("FIRST50", "₹50 off your order", "On medicines worth ₹199 or more.", Coupon.Kind.FLAT, "50", null, "199"),
                new Coupon("HEALTH10", "10% off up to ₹100", "On medicines worth ₹299 or more.", Coupon.Kind.PERCENT, "10", "100", "299"),
                new Coupon("CARE15", "15% off up to ₹150", "On medicines worth ₹599 or more.", Coupon.Kind.PERCENT, "15", "150", "599"),
                new Coupon("MEDI100", "Flat ₹100 off", "On medicines worth ₹999 or more.", Coupon.Kind.FLAT, "100", null, "999"),
                new Coupon("WELLNESS20", "20% off up to ₹300", "On medicines worth ₹1,499 or more.", Coupon.Kind.PERCENT, "20", "300", "1499")));
        log.info("Added sample promo codes");
    }

    /**
     * Hibernate writes a CHECK constraint listing an enum's values when it creates a table, and "ddl-auto=update"
     * never refreshes it, so inserting a newly added value (e.g. Role.PHARMACIST) fails on databases created by an
     * older version. The enum mapping already guards the values, so the constraints are dropped.
     */
    /**
     * Orders placed before invoices printed the patient: fill each line's patient in from who it was for (the account
     * holder's name, or the family member's patient name). Only empty lines are touched, so it runs safely every start.
     */
    private void backfillInvoicePatients() {
        jdbc.update("""
                update order_item i set patient_name = sub.names
                from (select x.order_item_id,
                             string_agg(distinct case m.kind when 'SELF' then u.full_name
                                                             when 'FAMILY' then coalesce(nullif(m.patient_name, ''), m.name) end, ', ') names
                      from order_item_members x
                      join family_members m on m.id = x.member_id
                      join users u on u.id = m.user_id
                      group by x.order_item_id) sub
                where i.id = sub.order_item_id and i.patient_name is null and sub.names is not null""");
        // Older still, from before family members: everything was for the account holder (deleted accounts stay blank)
        jdbc.update("""
                update order_item i set patient_name = u.full_name
                from orders o join users u on u.id = o.user_id
                where o.id = i.order_id and i.patient_name is null and u.deleted = false
                  and not exists (select 1 from order_item_members x where x.order_item_id = i.id)""");
    }

    private void dropStaleEnumChecks() {
        jdbc.execute("alter table if exists notification drop constraint if exists notification_type_check");
        jdbc.execute("alter table if exists family_members drop constraint if exists family_members_kind_check");
        jdbc.execute("alter table if exists users drop constraint if exists users_role_check");
        jdbc.execute("alter table if exists app_errors drop constraint if exists app_errors_severity_check");
        jdbc.execute("alter table if exists prescription drop constraint if exists prescription_status_check");
        jdbc.execute("alter table if exists orders drop constraint if exists orders_payment_method_check");
        jdbc.execute("alter table if exists return_requests drop constraint if exists return_requests_refund_mode_check");
        jdbc.execute("alter table if exists checkout_payments drop constraint if exists checkout_payments_method_check");
        jdbc.execute("alter table if exists checkout_payments drop constraint if exists checkout_payments_status_check");
        jdbc.execute("alter table if exists checkout_payments drop constraint if exists checkout_payments_gateway_check");
        jdbc.execute("alter table if exists orders drop constraint if exists orders_payment_gateway_check");
    }

    /**
     * Stock, expiry and delivery days moved from the product to each store's listing and PIN codes. The old columns
     * stay for the record, but must no longer be required, or adding a product would fail.
     */
    private void relaxLegacyColumns() {
        for (String column : new String[]{"stock", "expiry_date", "delivery_min_days", "delivery_max_days"}) {
            if (hasColumn("medicine", column)) jdbc.execute("alter table medicine alter column " + column + " drop not null");
        }
    }

    /** Units per pack became text ("100 ml", "30 g"); schema updates don't change a column's type, so it's done here. */
    private void unitsPerPackAsText() {
        String type = jdbc.query("select data_type from information_schema.columns where table_name = 'medicine' and column_name = 'units_per_pack'",
                rs -> rs.next() ? rs.getString(1) : null);
        if (type != null && !type.contains("char")) {
            // The old "at least 1" check compares a number, so it has to go before the column can hold text
            jdbc.execute("alter table medicine drop constraint if exists medicine_units_per_pack_check");
            jdbc.execute("alter table medicine alter column units_per_pack type varchar(20) using units_per_pack::text");
        }
    }

    private boolean hasColumn(String table, String column) {
        Integer n = jdbc.queryForObject("select count(*) from information_schema.columns where table_name = ? and column_name = ?",
                Integer.class, table, column);
        return n != null && n > 0;
    }

    private Store createMainStore() {
        Store s = new Store();
        s.setName(sellerName.isBlank() ? "MediZon Main Store" : sellerName.trim());
        s.setAddressLine(blankToNull(sellerAddress));
        s.setState(blankToNull(sellerState));
        s.setPhone(blankToNull(sellerPhone));
        s.setEmail(blankToNull(sellerEmail));
        s.setGstin(blankToNull(sellerGstin));
        s.setDrugLicense(blankToNull(sellerDrugLicense));
        return stores.save(s);
    }

    /**
     * A database from before stores had one pharmacy: its stock and expiry become the main store's catalogue, it
     * serves every PIN code (in 1 to 3 days, plus any extra days the PIN code had), and it gets the existing orders.
     */
    private void moveIntoStore(Store main) {
        boolean legacyStock = hasColumn("medicine", "stock");
        int listed = jdbc.update("insert into store_items (store_id, medicine_id, stock, expiry_date, active) "
                + "select ?, m.id, " + (legacyStock ? "coalesce(m.stock, 0), m.expiry_date" : "0, null") + ", true from medicine m "
                + "where not exists (select 1 from store_items i where i.store_id = ? and i.medicine_id = m.id)", main.getId(), main.getId());
        String extra = hasColumn("serviceable_pincode", "extra_delivery_days") ? "coalesce(p.extra_delivery_days, 0)" : "0";
        int served = jdbc.update("insert into store_areas (store_id, pincode, min_days, max_days) "
                + "select ?, p.pincode, 1 + " + extra + ", 3 + " + extra + " from serviceable_pincode p "
                + "where not exists (select 1 from store_areas a where a.store_id = ? and a.pincode = p.pincode)", main.getId(), main.getId());
        int assigned = jdbc.update("update orders set store_id = ? where store_id is null", main.getId());
        log.info("Moved the catalog into \"{}\": {} product(s) listed, {} PIN code(s) served, {} order(s) assigned",
                main.getName(), listed, served, assigned);
    }

    /**
     * There is exactly one admin, and its sign-in comes from MEDIZON_ADMIN_EMAIL / MEDIZON_ADMIN_PASSWORD: created when
     * missing, its password reset to the configured one when they differ. Any other admin account (from versions that
     * allowed several) is disabled; the admin can delete it or keep it switched off.
     */
    private void syncAdmin() {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            if (users.countByRole(Role.ADMIN) == 0) {
                log.warn("No admin account exists. Set MEDIZON_ADMIN_EMAIL and MEDIZON_ADMIN_PASSWORD and restart to create one.");
            } else {
                log.warn("MEDIZON_ADMIN_EMAIL / MEDIZON_ADMIN_PASSWORD aren't set; the existing admin account is left as it is.");
            }
            return;
        }
        String email = adminEmail.trim().toLowerCase();
        Optional<User> existing = users.findByEmailIgnoreCase(email);
        User admin;
        if (existing.isPresent()) {
            admin = existing.get();
            if (!admin.isAdmin()) {
                log.error("Can't make {} the admin: it's already a {} account. Use another email for MEDIZON_ADMIN_EMAIL.",
                        email, admin.getRole().getLabel().toLowerCase());
                return;
            }
            if (!encoder.matches(adminPassword, admin.getPassword())) {
                admin.setPassword(encoder.encode(adminPassword));
                log.info("Admin password updated from MEDIZON_ADMIN_PASSWORD.");
            }
        } else {
            admin = new User();
            admin.setEmail(email);
            admin.setPassword(encoder.encode(adminPassword));
            admin.setRole(Role.ADMIN);
            admin.setFullName("MediZon Admin");
            log.info("Created the admin account {}.", email);
        }
        if (!adminName.isBlank()) admin.setFullName(adminName.trim());
        admin.setEnabled(true);
        admin.setDeleted(false);
        users.save(admin);

        users.findByRole(Role.ADMIN).stream().filter(u -> !u.getId().equals(admin.getId()) && u.isEnabled()).forEach(u -> {
            u.setEnabled(false);
            log.warn("Disabled {}: there's only one admin now ({}).", u.getEmail(), email);
        });
    }

    private void seedCatalog(Store store) {
        med(store, "Paracetamol 650 mg", "Paracetamol 650 mg", "Micro Labs", "Pain & fever", "Tablet", 15, "33.50", "36.00", 400, false,
                "Relieves mild to moderate pain and lowers fever. Do not exceed the dose on the label; avoid combining with other paracetamol products.");
        med(store, "Ibuprofen 400 mg", "Ibuprofen 400 mg", "Abbott", "Pain & fever", "Tablet", 15, "28.00", "32.00", 250, false,
                "Anti-inflammatory pain reliever. Take with food. Not suitable for people with stomach ulcers or certain kidney conditions.");
        med(store, "Metformin 500 mg", "Metformin hydrochloride 500 mg", "USV", "Diabetes", "Tablet", 20, "42.00", "48.00", 300, true,
                "Lowers blood sugar in type 2 diabetes. Usually taken with meals.");
        med(store, "Glimepiride 1 mg", "Glimepiride 1 mg", "Sanofi", "Diabetes", "Tablet", 30, "96.00", "110.00", 120, true,
                "Helps the pancreas release insulin. Can cause low blood sugar if meals are skipped.");
        med(store, "Glucometer test strips (50)", "Blood glucose test strips", "Accu-Chek", "Devices", "Device", 50, "899.00", "1049.00", 60, false,
                "Test strips for home blood sugar monitoring. Check compatibility with your meter.");
        med(store, "Amlodipine 5 mg", "Amlodipine besylate 5 mg", "Cipla", "Heart & blood pressure", "Tablet", 30, "58.00", "65.00", 220, true,
                "Calcium channel blocker for high blood pressure and angina.");
        med(store, "Telmisartan 40 mg", "Telmisartan 40 mg", "Glenmark", "Heart & blood pressure", "Tablet", 30, "112.00", "128.00", 180, true,
                "Angiotensin receptor blocker used for high blood pressure.");
        med(store, "Atorvastatin 10 mg", "Atorvastatin calcium 10 mg", "Ranbaxy", "Heart & blood pressure", "Tablet", 15, "74.00", "85.00", 200, true,
                "Statin that lowers LDL cholesterol. Often taken in the evening.");
        med(store, "Levothyroxine 50 mcg", "Levothyroxine sodium 50 mcg", "Abbott", "Thyroid", "Tablet", 100, "165.00", "180.00", 150, true,
                "Thyroid hormone replacement. Take on an empty stomach, 30-60 minutes before breakfast.");
        med(store, "Cetirizine 10 mg", "Cetirizine hydrochloride 10 mg", "Dr. Reddy's", "Allergy", "Tablet", 10, "18.00", "22.00", 500, false,
                "Antihistamine for sneezing, runny nose and itching. May cause drowsiness.");
        med(store, "Pantoprazole 40 mg", "Pantoprazole 40 mg", "Alkem", "Stomach care", "Tablet", 15, "95.00", "110.00", 260, true,
                "Reduces stomach acid. Taken before breakfast.");
        med(store, "ORS sachet, orange", "Oral rehydration salts (WHO formula)", "FDC", "Stomach care", "Sachet", 1, "22.00", "22.00", 600, false,
                "Replaces fluids and salts lost through diarrhoea or vomiting. Dissolve one sachet in 1 litre of clean water.");
        med(store, "Azithromycin 500 mg", "Azithromycin 500 mg", "Alembic", "Antibiotics", "Tablet", 3, "71.00", "80.00", 140, true,
                "Antibiotic for bacterial infections. Complete the full course as prescribed.");
        med(store, "Dextromethorphan cough syrup", "Dextromethorphan + chlorpheniramine", "Mankind", "Cold & cough", "Syrup", 100, "105.00", "118.00", 160, false,
                "Relieves dry cough. Can cause drowsiness; avoid driving after a dose.");
        med(store, "Vitamin D3 60,000 IU", "Cholecalciferol 60,000 IU", "Sun Pharma", "Vitamins & supplements", "Capsule", 4, "130.00", "145.00", 300, false,
                "Weekly vitamin D supplement. Take as advised; usually once a week with milk or food.");
        med(store, "Multivitamin daily", "Multivitamin and minerals", "HealthKart", "Vitamins & supplements", "Tablet", 30, "349.00", "420.00", 180, false,
                "Daily multivitamin with zinc and B-complex.");
        med(store, "Omega-3 fish oil 1000 mg", "EPA + DHA", "Himalaya", "Vitamins & supplements", "Capsule", 60, "499.00", "599.00", 90, false,
                "Fish oil supplement. Take with a meal.");
        med(store, "Digital thermometer", "Clinical digital thermometer", "Dr. Trust", "Devices", "Device", 1, "199.00", "299.00", 75, false,
                "Reads temperature in about 60 seconds. Oral or underarm use.");
        log.info("Seeded the catalog with {} medicines in \"{}\"", medicines.count(), store.getName());
    }

    private void med(Store store, String name, String generic, String maker, String category, String form, int units,
                     String price, String mrp, int stock, boolean rx, String description) {
        Medicine m = new Medicine();
        m.setName(name);
        m.setGenericName(generic);
        m.setManufacturer(maker);
        m.setCategory(category);
        m.setDosageForm(form);
        m.setUnitsPerPack(String.valueOf(units));
        m.setPrice(new BigDecimal(price));
        m.setMrp(new BigDecimal(mrp));
        m.setPrescriptionRequired(rx);
        m.setDescription(description);
        medicines.save(m);
        StoreItem i = new StoreItem();
        i.setStore(store);
        i.setMedicine(m);
        i.setStock(stock);
        storeItems.save(i);
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
