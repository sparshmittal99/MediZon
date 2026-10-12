package com.medizon.config;

import com.medizon.domain.*;
import com.medizon.repository.MedicineRepository;
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

/** Seeds a starter catalog on an empty database and creates the first admin from configuration. */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private final UserRepository users;
    private final MedicineRepository medicines;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;

    @Value("${medizon.admin.email:}")
    private String adminEmail;

    @Value("${medizon.admin.password:}")
    private String adminPassword;

    @Value("${medizon.admin.name:}")
    private String adminName;

    @Override
    @Transactional
    public void run(String... args) {
        dropStaleEnumChecks();
        if (medicines.count() == 0) seedCatalog();
        if (users.countByRole(Role.ADMIN) == 0) seedFirstAdmin();
    }

    /**
     * Hibernate writes a CHECK constraint listing an enum's values when it creates a table, and "ddl-auto=update"
     * never refreshes it, so inserting a newly added value (e.g. NotificationType.RETURN_UPDATE) fails on databases
     * created by an older version. The enum mapping already guards the values, so the constraints are dropped.
     */
    private void dropStaleEnumChecks() {
        jdbc.execute("alter table if exists notification drop constraint if exists notification_type_check");
    }

    /** Creates the first admin from MEDIZON_ADMIN_EMAIL / MEDIZON_ADMIN_PASSWORD when no admin exists yet. */
    private void seedFirstAdmin() {
        if (adminEmail.isBlank() || adminPassword.isBlank()) {
            log.warn("No admin account exists. Set MEDIZON_ADMIN_EMAIL and MEDIZON_ADMIN_PASSWORD and restart to create one.");
            return;
        }
        if (users.existsByEmailIgnoreCase(adminEmail.trim())) {
            log.warn("Can't create the first admin: {} is already registered as a customer.", adminEmail.trim());
            return;
        }
        User admin = new User();
        admin.setFullName(adminName.isBlank() ? "MediZon Admin" : adminName.trim());
        admin.setEmail(adminEmail.trim().toLowerCase());
        admin.setPassword(encoder.encode(adminPassword));
        admin.setRole(Role.ADMIN);
        users.save(admin);
        log.info("Created the first admin account.");
    }

    private void seedCatalog() {
        med("Paracetamol 650 mg", "Paracetamol 650 mg", "Micro Labs", "Pain & fever", "Tablet", 15, "33.50", "36.00", 400, false,
                "Relieves mild to moderate pain and lowers fever. Do not exceed the dose on the label; avoid combining with other paracetamol products.");
        med("Ibuprofen 400 mg", "Ibuprofen 400 mg", "Abbott", "Pain & fever", "Tablet", 15, "28.00", "32.00", 250, false,
                "Anti-inflammatory pain reliever. Take with food. Not suitable for people with stomach ulcers or certain kidney conditions.");
        med("Metformin 500 mg", "Metformin hydrochloride 500 mg", "USV", "Diabetes", "Tablet", 20, "42.00", "48.00", 300, true,
                "Lowers blood sugar in type 2 diabetes. Usually taken with meals.");
        med("Glimepiride 1 mg", "Glimepiride 1 mg", "Sanofi", "Diabetes", "Tablet", 30, "96.00", "110.00", 120, true,
                "Helps the pancreas release insulin. Can cause low blood sugar if meals are skipped.");
        med("Glucometer test strips (50)", "Blood glucose test strips", "Accu-Chek", "Devices", "Device", 50, "899.00", "1049.00", 60, false,
                "Test strips for home blood sugar monitoring. Check compatibility with your meter.");
        med("Amlodipine 5 mg", "Amlodipine besylate 5 mg", "Cipla", "Heart & blood pressure", "Tablet", 30, "58.00", "65.00", 220, true,
                "Calcium channel blocker for high blood pressure and angina.");
        med("Telmisartan 40 mg", "Telmisartan 40 mg", "Glenmark", "Heart & blood pressure", "Tablet", 30, "112.00", "128.00", 180, true,
                "Angiotensin receptor blocker used for high blood pressure.");
        med("Atorvastatin 10 mg", "Atorvastatin calcium 10 mg", "Ranbaxy", "Heart & blood pressure", "Tablet", 15, "74.00", "85.00", 200, true,
                "Statin that lowers LDL cholesterol. Often taken in the evening.");
        med("Levothyroxine 50 mcg", "Levothyroxine sodium 50 mcg", "Abbott", "Thyroid", "Tablet", 100, "165.00", "180.00", 150, true,
                "Thyroid hormone replacement. Take on an empty stomach, 30-60 minutes before breakfast.");
        med("Cetirizine 10 mg", "Cetirizine hydrochloride 10 mg", "Dr. Reddy's", "Allergy", "Tablet", 10, "18.00", "22.00", 500, false,
                "Antihistamine for sneezing, runny nose and itching. May cause drowsiness.");
        med("Pantoprazole 40 mg", "Pantoprazole 40 mg", "Alkem", "Stomach care", "Tablet", 15, "95.00", "110.00", 260, true,
                "Reduces stomach acid. Taken before breakfast.");
        med("ORS sachet, orange", "Oral rehydration salts (WHO formula)", "FDC", "Stomach care", "Sachet", 1, "22.00", "22.00", 600, false,
                "Replaces fluids and salts lost through diarrhoea or vomiting. Dissolve one sachet in 1 litre of clean water.");
        med("Azithromycin 500 mg", "Azithromycin 500 mg", "Alembic", "Antibiotics", "Tablet", 3, "71.00", "80.00", 140, true,
                "Antibiotic for bacterial infections. Complete the full course as prescribed.");
        med("Dextromethorphan cough syrup", "Dextromethorphan + chlorpheniramine", "Mankind", "Cold & cough", "Syrup", 100, "105.00", "118.00", 160, false,
                "Relieves dry cough. Can cause drowsiness; avoid driving after a dose.");
        med("Vitamin D3 60,000 IU", "Cholecalciferol 60,000 IU", "Sun Pharma", "Vitamins & supplements", "Capsule", 4, "130.00", "145.00", 300, false,
                "Weekly vitamin D supplement. Take as advised; usually once a week with milk or food.");
        med("Multivitamin daily", "Multivitamin and minerals", "HealthKart", "Vitamins & supplements", "Tablet", 30, "349.00", "420.00", 180, false,
                "Daily multivitamin with zinc and B-complex.");
        med("Omega-3 fish oil 1000 mg", "EPA + DHA", "Himalaya", "Vitamins & supplements", "Capsule", 60, "499.00", "599.00", 90, false,
                "Fish oil supplement. Take with a meal.");
        med("Digital thermometer", "Clinical digital thermometer", "Dr. Trust", "Devices", "Device", 1, "199.00", "299.00", 75, false,
                "Reads temperature in about 60 seconds. Oral or underarm use.");
        log.info("Seeded catalog with {} medicines", medicines.count());
    }

    private void med(String name, String generic, String maker, String category, String form, int units,
                     String price, String mrp, int stock, boolean rx, String description) {
        Medicine m = new Medicine();
        m.setName(name);
        m.setGenericName(generic);
        m.setManufacturer(maker);
        m.setCategory(category);
        m.setDosageForm(form);
        m.setUnitsPerPack(units);
        m.setPrice(new BigDecimal(price));
        m.setMrp(new BigDecimal(mrp));
        m.setStock(stock);
        m.setPrescriptionRequired(rx);
        m.setDescription(description);
        medicines.save(m);
    }
}
