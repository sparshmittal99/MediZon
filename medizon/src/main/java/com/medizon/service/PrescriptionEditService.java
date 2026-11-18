package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.Prescription;
import com.medizon.domain.PrescriptionEdit;
import com.medizon.domain.User;
import com.medizon.dto.PrescriptionReading;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The customer's edit screen for an uploaded prescription: they can correct anything the AI read (patient, doctor,
 * date, the medicines) and add a note, until the pharmacist has reviewed it. The AI's reading is kept as it was; the
 * corrected details are saved beside it, and every changed field is logged (field, what the AI read, before, after,
 * who) for the pharmacist's edit history.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionEditService {

    public static final int MAX_MEDICINES = 20;

    private final PrescriptionRepository prescriptions;
    private final MedicineRepository medicines;
    private final PrescriptionScanService scan;

    /** One medicine as the customer sees and edits it. */
    public record Med(String name, String strength, String dosage, String duration) {
        boolean isBlank() { return blank(name) && blank(strength) && blank(dosage) && blank(duration); }

        /** "Dolo 650 (650 mg), 1-0-1 for 5 days". */
        String summary() {
            StringBuilder s = new StringBuilder(blank(name) ? "?" : name.trim());
            if (!blank(strength)) s.append(" (").append(strength.trim()).append(")");
            if (!blank(dosage)) s.append(", ").append(dosage.trim());
            if (!blank(duration)) s.append(" for ").append(duration.trim());
            return s.toString();
        }
    }

    /** Every field on the edit screen. */
    public record Form(String patientName, String doctorName, LocalDate prescribedOn, String notes, List<Med> medicines) {}

    /** The customer's own prescription, while they can still edit it. */
    public Prescription editable(User user, Long id) {
        Prescription p = prescriptions.findByIdAndUser(id, user).filter(x -> !x.isRemoved())
                .orElseThrow(() -> new NotFoundException("Prescription not found."));
        if (!p.isEditable()) throw new BusinessException("This prescription has been reviewed, so it can't be changed. Upload a new one if something's wrong.");
        return p;
    }

    /** The form as it stands: the saved details, and the medicines as last corrected (or as the AI read them). */
    public Form formFor(Prescription p) {
        List<Med> meds = scan.current(p).map(r -> r.medicines().stream().map(PrescriptionEditService::med).toList()).orElse(List.of());
        return new Form(p.getPatientName(), p.getDoctorName(), p.getPrescribedOn(), p.getNotes(), meds);
    }

    /** What the AI read, for the pharmacist's history and the edit screen's hints; empty when it read nothing. */
    public Optional<PrescriptionReading> aiReading(Prescription p) { return scan.reading(p); }

    /** Saves the form; returns how many fields changed (zero when nothing did). */
    @Transactional
    public int save(User user, Long id, Form f) {
        Prescription p = editable(user, id);
        String by = user.getFullName() == null || user.getFullName().isBlank() ? user.getEmail() : user.getFullName();
        Optional<PrescriptionReading> ai = scan.reading(p);
        Form before = formFor(p);
        int changed = 0;

        // The details printed on the invoice; the pharmacist confirms them when approving
        String patient = clean(f.patientName());
        if (patient != null && patient.length() > 100) throw new BusinessException("Keep the patient's name under 100 characters.");
        String doctor = clean(f.doctorName());
        if (doctor != null) doctor = doctor.replaceFirst("(?i)^dr\\.?\\s+", "");
        if (doctor != null && doctor.length() > 255) throw new BusinessException("Keep the doctor's name under 255 characters.");
        LocalDate date = f.prescribedOn();
        if (date != null && date.isAfter(LocalDate.now())) throw new BusinessException("The prescription date can't be in the future.");
        if (date != null && date.isBefore(LocalDate.now().minusYears(5))) throw new BusinessException("Check the prescription date: it's more than 5 years ago.");
        String notes = clean(f.notes());
        if (notes != null && notes.length() > 1000) throw new BusinessException("Keep the note for the pharmacist under 1000 characters.");

        changed += log(p, "Patient", ai.map(PrescriptionReading::patientName).orElse(null), before.patientName(), patient, by);
        changed += log(p, "Doctor", ai.map(PrescriptionReading::doctorName).orElse(null), before.doctorName(), doctor, by);
        changed += log(p, "Rx date", ai.map(r -> blank(r.prescribedOnIso()) ? r.prescribedOn() : r.prescribedOnIso()).orElse(null),
                iso(before.prescribedOn()), iso(date), by);
        changed += log(p, "Note", null, before.notes(), notes, by);
        p.setPatientName(patient);
        p.setDoctorName(doctor);
        p.setPrescribedOn(date);
        p.setNotes(notes);

        // The medicines, compared line by line
        List<Med> meds = (f.medicines() == null ? List.<Med>of() : f.medicines()).stream()
                .filter(m -> m != null && !m.isBlank()).map(PrescriptionEditService::trimmed).toList();
        if (meds.size() > MAX_MEDICINES) throw new BusinessException("Add at most " + MAX_MEDICINES + " medicines.");
        for (Med m : meds) {
            if (blank(m.name())) throw new BusinessException("Give each medicine its name, as written on the prescription.");
            if (m.name().length() > 120) throw new BusinessException("Keep each medicine's name under 120 characters.");
            if (len(m.strength()) > 60 || len(m.dosage()) > 60 || len(m.duration()) > 60) {
                throw new BusinessException("Keep each medicine's strength, dose and duration under 60 characters.");
            }
        }
        List<PrescriptionReading.Line> aiLines = ai.map(PrescriptionReading::medicines).orElse(List.of());
        List<Med> old = before.medicines();
        int medChanges = 0;
        for (int k = 0; k < Math.max(old.size(), meds.size()); k++) {
            String n = "Med " + (k + 1);
            Med was = k < old.size() ? old.get(k) : null;
            Med now = k < meds.size() ? meds.get(k) : null;
            Med read = k < aiLines.size() ? med(aiLines.get(k)) : null;
            if (was == null) {
                medChanges += log(p, n + " added", read == null ? null : read.summary(), null, now.summary(), by);
            } else if (now == null) {
                medChanges += log(p, n + " removed", read == null ? null : read.summary(), was.summary(), null, by);
            } else {
                medChanges += log(p, n + " name", read == null ? null : read.name(), was.name(), now.name(), by);
                medChanges += log(p, n + " strength", read == null ? null : read.strength(), was.strength(), now.strength(), by);
                medChanges += log(p, n + " dose", read == null ? null : read.dosage(), was.dosage(), now.dosage(), by);
                medChanges += log(p, n + " duration", read == null ? null : read.duration(), was.duration(), now.duration(), by);
            }
        }
        if (medChanges > 0 || (p.getCustomerReading() != null && changed > 0)) {
            p.setCustomerReading(scan.write(corrected(ai.orElse(null), p, meds)));
        }
        return changed + medChanges;
    }

    /**
     * The corrected reading: the AI's flags kept, the details and medicines as the customer has them. A line keeps the
     * AI's product match while its name is unchanged; a renamed or new one is matched to a product of the same name.
     */
    private PrescriptionReading corrected(PrescriptionReading ai, Prescription p, List<Med> meds) {
        List<PrescriptionReading.Line> aiLines = ai == null ? List.of() : ai.medicines();
        List<Medicine> catalogue = null;
        List<PrescriptionReading.Line> lines = new ArrayList<>();
        for (Med m : meds) {
            PrescriptionReading.Line same = aiLines.stream().filter(l -> l.writtenAs() != null && l.writtenAs().trim().equalsIgnoreCase(m.name()))
                    .findFirst().orElse(null);
            Long catalogId = same == null ? null : same.catalogId();
            Integer units = same == null ? null : same.unitsNeeded();
            if (same == null) {
                if (catalogue == null) catalogue = medicines.findByActiveTrueOrderByNameAsc();
                catalogId = match(catalogue, m.name()).map(Medicine::getId).orElse(null);
            }
            lines.add(new PrescriptionReading.Line(m.name(), same == null ? null : same.genericName(), m.strength(), m.dosage(),
                    m.duration(), units, catalogId, same == null ? "customer" : same.confidence()));
        }
        return new PrescriptionReading(p.getDoctorName(), ai == null ? null : ai.registrationNumber(), p.getPatientName(),
                p.getPrescribedOn() == null ? null : p.getPrescribedOn().toString(), iso(p.getPrescribedOn()),
                ai == null || ai.legible() || !meds.isEmpty(), ai != null && ai.signed(), ai == null ? List.of() : ai.concerns(), lines);
    }

    /** The product a typed name means: the same name, else the only product whose name starts with it. */
    static Optional<Medicine> match(List<Medicine> catalogue, String name) {
        String n = name.trim().toLowerCase(Locale.ROOT);
        Optional<Medicine> exact = catalogue.stream().filter(m -> m.getName().trim().toLowerCase(Locale.ROOT).equals(n)).findFirst();
        if (exact.isPresent() || n.length() < 4) return exact;
        List<Medicine> starts = catalogue.stream().filter(m -> m.getName().toLowerCase(Locale.ROOT).startsWith(n)).limit(2).toList();
        return starts.size() == 1 ? Optional.of(starts.get(0)) : Optional.empty();
    }

    /** Logs one field when it changed; returns 1 if it did. */
    private static int log(Prescription p, String field, String aiValue, String before, String after, String by) {
        if (Objects.equals(norm(before), norm(after))) return 0;
        p.getEdits().add(new PrescriptionEdit(p, field, aiValue, before, after, by));
        return 1;
    }

    private static Med med(PrescriptionReading.Line l) {
        return new Med(l.writtenAs(), l.strength(), l.dosage(), l.duration());
    }

    private static Med trimmed(Med m) {
        return new Med(clean(m.name()), clean(m.strength()), clean(m.dosage()), clean(m.duration()));
    }

    private static String iso(LocalDate d) { return d == null ? null : d.toString(); }

    /** Parses a yyyy-MM-dd form value; null when blank or unreadable. */
    public static LocalDate date(String s) {
        if (blank(s)) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException("Enter the prescription date as a date.");
        }
    }

    private static String norm(String s) { return blank(s) ? null : s.trim().replaceAll("\\s+", " "); }

    private static String clean(String s) { return blank(s) ? null : s.trim(); }

    private static int len(String s) { return s == null ? 0 : s.length(); }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
