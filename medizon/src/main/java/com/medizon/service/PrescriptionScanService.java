package com.medizon.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medizon.ai.PrescriptionReader;
import com.medizon.domain.Medicine;
import com.medizon.domain.Prescription;
import com.medizon.dto.PrescriptionReading;
import com.medizon.dto.PrescriptionScore;
import com.medizon.dto.Product;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * "Upload a prescription, get your cart": an AI model reads the prescription and its medicines are matched to the
 * catalogue, pre-filling what the pharmacist approves; the approved medicines then go into the cart by themselves. Each reading is scored ({@link PrescriptionScore})
 * so the pharmacist can fast-track the clear ones; the pharmacist still approves every prescription. When the AI is
 * down the upload simply goes to the pharmacist as before.
 */
@Slf4j
@Service
public class PrescriptionScanService {

    private final Optional<PrescriptionReader> reader;
    private final PrescriptionRepository prescriptions;
    private final MedicineRepository medicines;
    private final FileStorageService storage;
    private final CatalogService catalog;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final int fastTrackScore;

    public PrescriptionScanService(List<PrescriptionReader> readers, PrescriptionRepository prescriptions,
                                   MedicineRepository medicines, FileStorageService storage, CatalogService catalog,
                                   ObjectMapper json, PlatformTransactionManager txManager,
                                   @Value("${medizon.ai.fast-track-score:80}") int fastTrackScore) {
        this.reader = readers.stream().filter(PrescriptionReader::isEnabled).findFirst();
        this.prescriptions = prescriptions;
        this.medicines = medicines;
        this.storage = storage;
        this.catalog = catalog;
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
        this.fastTrackScore = fastTrackScore;
    }

    public boolean isEnabled() { return reader.isPresent(); }

    /**
     * Reads and scores the prescription and saves the result. Runs outside a transaction: the AI can take several
     * seconds and shouldn't hold a database connection meanwhile. Never throws: when the AI is down, out of quota or
     * fails, the failure is saved (the page offers a retry) and the prescription waits for the pharmacist as usual.
     */
    public void scan(Long prescriptionId) {
        if (reader.isEmpty()) return;
        PrescriptionReading reading = null;
        PrescriptionScore score = null;
        try {
            Prescription p = prescriptions.findById(prescriptionId).orElseThrow(() -> new NotFoundException("Prescription not found."));
            if (reader.get().isAvailable()) {      // down a moment ago: don't make the customer wait for it again
                List<Medicine> catalogue = medicines.findByActiveTrueOrderByNameAsc();
                byte[] file = storage.load(p.getStoredFileName()).getContentAsByteArray();
                reading = reader.get().read(file, p.getContentType(), catalogue);
                score = PrescriptionScore.of(reading, catalogue.stream().map(Medicine::getId).collect(Collectors.toSet()),
                        LocalDate.now(), fastTrackScore);
            }
        } catch (Exception e) {
            log.warn("Couldn't read prescription {} with AI: {}", prescriptionId, e.getMessage());
        }
        PrescriptionReading read = reading;
        PrescriptionScore scored = score;
        tx.executeWithoutResult(s -> prescriptions.findById(prescriptionId).ifPresent(q -> {
            q.setAiReadAt(LocalDateTime.now());
            q.setAiReading(read == null ? null : write(read));
            q.setAiSummary(read == null ? null : summary(read));
            q.setAiScore(scored == null ? null : scored.total());
            q.setFastTrack(scored != null && scored.fastTrack());
            if (read == null) return;
            // Fill in what the customer didn't type; the pharmacist confirms all three when approving
            if (blank(q.getDoctorName()) && !blank(read.doctorName())) q.setDoctorName(trim(read.doctorName(), 255));
            if (blank(q.getPatientName()) && !blank(read.patientName())) q.setPatientName(trim(read.patientName(), 100));
            if (q.getPrescribedOn() == null && !blank(read.prescribedOnIso())) {
                try {
                    q.setPrescribedOn(LocalDate.parse(read.prescribedOnIso().trim()));
                } catch (java.time.format.DateTimeParseException ignored) {
                    // unreadable date: the pharmacist enters it
                }
            }
        }));
    }

    /** The score with its checks, for the pharmacist and admin; recomputed so the date check stays current. */
    public Optional<PrescriptionScore> score(Prescription p) {
        return reading(p).map(r -> {
            Set<Long> ids = r.medicines().stream().map(PrescriptionReading.Line::catalogId).filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            Set<Long> known = medicines.findAllById(ids).stream().filter(Medicine::isActive).map(Medicine::getId)
                    .collect(Collectors.toSet());
            return PrescriptionScore.of(r, known, LocalDate.now(), fastTrackScore);
        });
    }

    public Optional<PrescriptionReading> reading(Prescription p) {
        if (p.getAiReading() == null) return Optional.empty();
        try {
            return Optional.of(json.readValue(p.getAiReading(), PrescriptionReading.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * One row per medicine read, with the product we'd sell for it here and how many packs cover the course.
     *
     * @param product null when the catalogue doesn't have it
     */
    public record Suggestion(PrescriptionReading.Line line, Product product, int packs) {
        public boolean canAdd() { return product != null && product.isInStock(); }
    }

    public List<Suggestion> suggestions(PrescriptionReading reading, String pincode) {
        Set<Long> ids = reading.medicines().stream().map(PrescriptionReading.Line::catalogId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        // The AI only picks from the ids it was given, but check: inactive or made-up ids count as "not found"
        Map<Long, Medicine> found = medicines.findAllById(ids).stream().filter(Medicine::isActive)
                .collect(Collectors.toMap(Medicine::getId, m -> m));
        Map<Long, Product> products = catalog.products(new ArrayList<>(found.values()), pincode).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));
        return reading.medicines().stream().map(l -> {
            Product product = l.catalogId() == null ? null : products.get(l.catalogId());
            return new Suggestion(l, product, product == null ? 1 : packs(l.unitsNeeded(), product.getUnitsPerPack()));
        }).toList();
    }

    /** Packs covering the course: 10 tablets in strips of 15 is 1 pack. One pack when the course isn't clear. */
    static int packs(Integer unitsNeeded, int unitsPerPack) {
        if (unitsNeeded == null || unitsNeeded <= 0 || unitsPerPack <= 0) return 1;
        return Math.max(1, Math.min(5, (unitsNeeded + unitsPerPack - 1) / unitsPerPack));
    }

    /** One medicine row of the pharmacist's approval form. */
    /**
     * One medicine row of the pharmacist's approval form.
     *
     * @param fromAi read on the prescription by the AI
     * @param fromCart in the customer's cart for this patient when they sent it
     */
    public record CoverRow(Long medicineId, int packs, boolean fromAi, boolean fromCart) {}

    /**
     * The approval form's rows, pre-filled from what the AI read (with the packs for the course) and what the customer
     * has in the cart for this patient (with the packs they want); where both have it, the larger number of packs. The
     * pharmacist checks the rows against the file.
     */
    public List<CoverRow> suggestedCover(Prescription p) {
        Map<Long, Integer> ai = new LinkedHashMap<>();
        reading(p).ifPresent(r -> {
            Map<Long, Medicine> found = medicines.findAllById(r.medicines().stream().map(PrescriptionReading.Line::catalogId)
                            .filter(Objects::nonNull).toList()).stream().filter(Medicine::isActive)
                    .collect(Collectors.toMap(Medicine::getId, m -> m));
            for (PrescriptionReading.Line l : r.medicines()) {
                Medicine m = l.catalogId() == null ? null : found.get(l.catalogId());
                if (m != null) ai.merge(m.getId(), packs(l.unitsNeeded(), m.getUnitsPerPack()), Integer::sum);
            }
        });
        Map<Long, Integer> cart = p.getRequestedPacks();
        Set<Long> ids = new LinkedHashSet<>(ai.keySet());
        ids.addAll(cart.keySet());
        return ids.stream().map(id -> new CoverRow(id, Math.max(ai.getOrDefault(id, 0), cart.getOrDefault(id, 0)),
                ai.containsKey(id), cart.containsKey(id))).toList();
    }

    /** Reads several just-uploaded prescriptions at once, a few at a time; returns when all are done (or after a minute). */
    public void scanAll(List<Long> ids) {
        if (reader.isEmpty() || ids.isEmpty()) return;
        if (ids.size() == 1) { scan(ids.get(0)); return; }
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(3, ids.size()));
        try {
            List<Future<?>> running = ids.stream().map(id -> pool.submit(() -> scan(id))).collect(Collectors.toList());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (Future<?> f : running) {
                try {
                    f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    f.cancel(true);      // the pharmacist reads it instead
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (ExecutionException e) {
                    log.warn("Prescription scan failed", e.getCause());
                }
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private String write(PrescriptionReading r) {
        try {
            return json.writeValueAsString(r);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String summary(PrescriptionReading r) {
        if (!r.legible() || r.medicines().isEmpty()) return "AI couldn't read any medicines.";
        return trim(r.medicines().stream().map(PrescriptionReading.Line::getSummary).collect(Collectors.joining("; ")), 1000);
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }

    private static String trim(String s, int max) { return s.length() <= max ? s : s.substring(0, max - 1) + "…"; }
}
