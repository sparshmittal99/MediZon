package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Prescriptions: upload, the pharmacist's review, and what each approved one covers. The pharmacist approves the
 * medicines on it and how many packs; orders of prescription medicines must be on the chosen prescription and within
 * the packs left. Once everything on it has been ordered it's used up, and the customer needs a new one.
 */
@Service
@RequiredArgsConstructor
public class PrescriptionService {

    /** Most packs of one medicine a pharmacist can approve on one prescription. */
    public static final int MAX_PACKS = 99;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final PrescriptionRepository prescriptions;
    private final MedicineRepository medicines;
    private final FileStorageService storage;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;
    private final ApplicationEventPublisher events;
    private final CartItemRepository cartItems;
    private final MemberService memberService;

    /** Most files in one upload: one prescription each, e.g. for several family members. */
    public static final int MAX_FILES = 5;

    /** Published when a pharmacist approves a prescription, to put its medicines in the customer's cart. */
    public record Approved(Long prescriptionId) {}

    /** Saves an uploaded file as a draft: the customer checks it before it goes to the pharmacist. */
    @Transactional
    public Prescription upload(User user, MultipartFile file) {
        Prescription p = new Prescription();
        p.setUser(user);
        p.setStoredFileName(storage.store(file));
        p.setOriginalFileName(file.getOriginalFilename());
        p.setContentType(file.getContentType());
        p.setStatus(PrescriptionStatus.DRAFT);
        return prescriptions.save(p);
    }

    /** Uploaded but not sent yet, newest first. */
    public List<Prescription> drafts(User user) {
        return forUser(user).stream().filter(p -> p.getStatus() == PrescriptionStatus.DRAFT).toList();
    }

    /**
     * The customer removes a prescription, whatever its status. One never used for an order is deleted with its file;
     * one that was is archived instead (hidden from them and never used again) because the pharmacy must keep the
     * prescriptions it dispensed against. Returns true when it was archived rather than deleted.
     */
    @Transactional
    public boolean remove(User user, Long id) {
        Prescription p = prescriptions.findByIdAndUser(id, user).filter(x -> !x.isRemoved())
                .orElseThrow(() -> new NotFoundException("Prescription not found."));
        if (prescriptions.usedForOrders(p)) {
            p.setRemoved(true);
            return true;
        }
        prescriptions.delete(p);
        storage.delete(p.getStoredFileName());
        return false;
    }

    /** What the customer adds when sending a draft. */
    public record Note(String doctorName, String notes) {}

    /**
     * Sends drafts to the pharmacist. Each records what the customer has in the cart for its patient, so the
     * pharmacist's list arrives pre-filled; then the pharmacists are alerted.
     */
    @Transactional
    public List<Prescription> submit(User user, Map<Long, Note> notes) {
        List<Prescription> sent = new ArrayList<>();
        notes.forEach((id, note) -> {
            Prescription p = prescriptions.findByIdAndUser(id, user).orElseThrow(() -> new NotFoundException("Prescription not found."));
            if (p.getStatus() != PrescriptionStatus.DRAFT) return;
            if (note != null && note.doctorName() != null && !note.doctorName().isBlank()) {
                String d = note.doctorName().trim().replaceFirst("(?i)^dr\\.?\\s+", "");
                if (d.length() > 255) throw new BusinessException("Keep the doctor's name under 255 characters.");
                p.setDoctorName(d);
            }
            if (note != null && note.notes() != null) {
                String n = note.notes().trim();
                if (n.length() > 1000) throw new BusinessException("Keep notes for the pharmacist under 1000 characters.");
                p.setNotes(n.isEmpty() ? null : n);
            }
            p.getRequestedPacks().clear();
            p.getRequestedPacks().putAll(requestedFromCart(user, p));
            p.setStatus(PrescriptionStatus.PENDING);
            p.setUploadedAt(LocalDateTime.now());       // the queue counts from when it was sent
            sent.add(p);
        });
        sent.forEach(p -> alertPharmacists(p.getId()));
        return sent;
    }

    /**
     * The prescription medicines in the cart this new prescription is for, minus what the customer's approved
     * prescriptions already cover (those don't need approving again): the uncovered lines (or shares of lines) for the
     * member its patient name matches, or for "Others" when it matches nobody; every uncovered line when that finds
     * nothing, or when there's no patient name to go by.
     */
    Map<Long, Integer> requestedFromCart(User user, Prescription p) {
        List<CartItem> rxLines = cartItems.findByUserOrderByIdAsc(user).stream()
                .filter(c -> c.getMedicine().isPrescriptionRequired()).toList();
        // Packs of each medicine the approved prescriptions still allow
        Map<Long, Integer> covered = new HashMap<>();
        validForUser(user).forEach(rx -> rx.getItems().forEach(i -> covered.merge(i.getMedicine().getId(), i.getRemaining(), Integer::sum)));
        Map<Long, Integer> uncovered = new LinkedHashMap<>();
        rxLines.forEach(c -> {
            int n = c.getQuantity() - covered.getOrDefault(c.getMedicine().getId(), 0);
            if (n > 0) uncovered.merge(c.getMedicine().getId(), n, Integer::sum);
        });
        if (uncovered.isEmpty()) return uncovered;

        Map<Long, Integer> forPatient = new LinkedHashMap<>();
        if (p.getPatientName() != null && !p.getPatientName().isBlank()) {
            Member who = memberService.byPatientName(user, p.getPatientName()).orElseGet(() -> memberService.others(user));
            rxLines.forEach(c -> {
                int n = Math.min(c.packsFor(who.getId()), uncovered.getOrDefault(c.getMedicine().getId(), 0));
                if (n > 0) forPatient.merge(c.getMedicine().getId(), n, Integer::sum);
            });
        }
        return forPatient.isEmpty() ? uncovered : forPatient;
    }

    /**
     * Tells the pharmacists a prescription is waiting, once the AI has had its go at it (or was skipped because it's
     * off or down). Clear, high-scoring ones say so, for one-click approval.
     */
    @Transactional
    public void alertPharmacists(Long id) {
        prescriptions.findById(id).filter(p -> p.getStatus() == PrescriptionStatus.PENDING).ifPresent(p -> {
            String doctor = p.getDoctorName() == null || p.getDoctorName().isBlank() ? "." : " from Dr. " + p.getDoctorName().trim() + ".";
            String score = p.getAiScore() == null ? "" : " AI score " + p.getAiScore() + "/100.";
            staffAlerts.notify(Role.PHARMACIST, NotificationType.PRESCRIPTION_UPDATE,
                    p.isFastTrack() ? "Fast-track prescription to approve" : "Prescription to review",
                    p.getUser().getFullName() + " uploaded a prescription" + doctor + score, "/admin/prescriptions");
        });
    }

    /** The customer's prescriptions, newest first, minus those they removed. */
    public List<Prescription> forUser(User user) {
        return prescriptions.findByUserOrderByUploadedAtDesc(user).stream().filter(p -> !p.isRemoved()).toList();
    }

    /** Approved, in date and not used up. */
    public List<Prescription> validForUser(User user) {
        return prescriptions.findByUserAndStatusOrderByReviewedAtDesc(user, PrescriptionStatus.APPROVED)
                .stream().filter(Prescription::isValid).toList();
    }

    /** Waiting for review: fast-track first, then by AI score (unscored last), oldest first within each. */
    public List<Prescription> pending() {
        return prescriptions.findByStatusOrderByUploadedAtAsc(PrescriptionStatus.PENDING).stream()
                .sorted(Comparator.comparing(Prescription::isFastTrack).reversed()
                        .thenComparing(Prescription::getAiScore, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    public List<Prescription> recentlyReviewed() {
        // Only what a pharmacist decided on: never a customer's unsent upload
        return prescriptions.findTop30ByStatusInOrderByReviewedAtDesc(
                EnumSet.of(PrescriptionStatus.APPROVED, PrescriptionStatus.REJECTED, PrescriptionStatus.USED_UP));
    }

    /**
     * The pharmacist's decision. Approving records the medicines it covers and how many packs of each
     * ({@code packsByMedicine}, at least one); they then go into the customer's cart.
     */
    /** What the pharmacist confirms from the prescription itself; printed on the invoice. */
    public record Details(String doctorName, String patientName, LocalDate prescribedOn) {}

    @Transactional
    public void review(Long id, boolean approve, String note, LocalDate validUntil, Map<Long, Integer> packsByMedicine,
                       Details details) {
        Prescription p = prescriptions.findById(id)
                .orElseThrow(() -> new NotFoundException("Prescription not found."));
        if (p.getStatus() != PrescriptionStatus.PENDING) throw new BusinessException("This prescription has already been reviewed.");
        if (approve) {
            String doctor = details == null || details.doctorName() == null ? "" : details.doctorName().trim().replaceFirst("(?i)^dr\\.?\\s+", "");
            String patient = details == null || details.patientName() == null ? "" : details.patientName().trim();
            LocalDate date = details == null ? null : details.prescribedOn();
            if (doctor.isEmpty() || patient.isEmpty() || date == null) {
                throw new BusinessException("Enter the doctor's name, the patient's name and the prescription date before approving; they're printed on the invoice.");
            }
            if (date.isAfter(LocalDate.now())) throw new BusinessException("The prescription date can't be in the future.");
            if (doctor.length() > 255 || patient.length() > 100) throw new BusinessException("The doctor's or patient's name is too long.");
            p.setDoctorName(doctor);
            p.setPatientName(patient);
            p.setPrescribedOn(date);
            if (packsByMedicine == null || packsByMedicine.isEmpty()) {
                throw new BusinessException("Add the medicines this prescription covers, with the packs prescribed, before approving.");
            }
            p.getItems().clear();
            packsByMedicine.forEach((medicineId, packs) -> {
                Medicine m = medicines.findById(medicineId).orElseThrow(() -> new BusinessException("Choose medicines from the catalogue."));
                if (packs == null || packs < 1 || packs > MAX_PACKS) {
                    throw new BusinessException("Enter between 1 and " + MAX_PACKS + " packs for " + m.getName() + ".");
                }
                p.getItems().add(new PrescriptionItem(p, m, packs));
            });
        }
        p.setStatus(approve ? PrescriptionStatus.APPROVED : PrescriptionStatus.REJECTED);
        p.setReviewerNote(note);
        p.setValidUntil(approve ? (validUntil != null ? validUntil : LocalDate.now().plusMonths(6)) : null);
        p.setReviewedAt(LocalDateTime.now());

        if (approve) {
            events.publishEvent(new Approved(p.getId()));       // fills the cart and tells the customer
        } else {
            notifications.notify(p.getUser(), NotificationType.PRESCRIPTION_UPDATE, "Prescription needs attention",
                    "Your prescription couldn't be approved" + (note == null || note.isBlank() ? "." : ": " + note)
                            + " Upload a clearer copy to continue.", "/prescriptions");
        }
    }

    // ------------------------------------------------------------------ covering a cart

    /** A prescription medicine to cover: the packs wanted, and the patients it's for (to prefer their prescription). */
    public record RxLine(Medicine medicine, int packs, Set<String> patients) {}

    /** Packs of a medicine taken from one prescription. */
    public record Take(PrescriptionItem item, int packs) {}

    /**
     * How a cart's prescription medicines are covered by the customer's prescriptions: which prescription(s) each one
     * comes from, and a sentence for each medicine that can't be covered.
     */
    public record Coverage(Map<Long, List<Take>> takes, List<String> problems) {

        public static final Coverage NONE = new Coverage(Map.of(), List.of());

        public boolean isComplete() { return problems.isEmpty(); }

        public List<Take> forMedicine(Long medicineId) { return takes.getOrDefault(medicineId, List.of()); }

        /** For checkout: "Dr. Ambrose's prescription of 4 Oct 2026" (or several, joined). */
        public String sourceOf(Long medicineId) {
            return forMedicine(medicineId).stream().map(t -> label(t.item().getPrescription())).distinct()
                    .collect(Collectors.joining(" + "));
        }

        public Set<Prescription> prescriptions() {
            Set<Prescription> out = new LinkedHashSet<>();
            takes.values().forEach(l -> l.forEach(t -> out.add(t.item().getPrescription())));
            return out;
        }

        /** Throws the problems as one message, naming only what isn't covered. */
        public void require() {
            if (problems.isEmpty()) return;
            throw new BusinessException(String.join(" ", problems) + " Remove " + (problems.size() == 1 ? "it" : "them")
                    + " from the cart or change the quantity, or upload a prescription that includes "
                    + (problems.size() == 1 ? "it." : "them."));
        }

        private static String label(Prescription p) {
            String doctor = p.getDoctorName() == null || p.getDoctorName().isBlank() ? "Prescription" : "Dr. " + p.getDoctorName().trim();
            LocalDate when = p.getPrescribedOn() != null ? p.getPrescribedOn() : p.getUploadedAt().toLocalDate();
            return doctor + ", " + when.format(DAY);
        }
    }

    /** Covers the lines from the customer's valid prescriptions. */
    public Coverage cover(User user, List<RxLine> lines) {
        return lines.isEmpty() ? Coverage.NONE : cover(validForUser(user), lines);
    }

    /**
     * Covers each line from the prescriptions that have its medicine with packs left: first those written for the
     * line's patient, then the most recently approved, splitting a line across prescriptions when one hasn't enough.
     * A line that can't be covered in full takes nothing and gets a sentence in the problems.
     */
    static Coverage cover(List<Prescription> valid, List<RxLine> lines) {
        Map<PrescriptionItem, Integer> left = new IdentityHashMap<>();
        valid.forEach(p -> p.getItems().forEach(i -> left.put(i, i.getRemaining())));
        Map<Long, List<Take>> takes = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (RxLine line : lines) {
            Medicine m = line.medicine();
            List<PrescriptionItem> candidates = valid.stream()
                    .sorted(Comparator.comparing((Prescription p) -> !isFor(p, line.patients())))    // stable: newest stays first
                    .flatMap(p -> p.itemFor(m.getId()).stream())
                    .toList();
            int need = line.packs();
            List<Take> taken = new ArrayList<>();
            for (PrescriptionItem i : candidates) {
                int n = Math.min(need, left.get(i));
                if (n <= 0) continue;
                taken.add(new Take(i, n));
                need -= n;
                if (need == 0) break;
            }
            if (need == 0) {
                taken.forEach(t -> left.merge(t.item(), -t.packs(), Integer::sum));
                takes.put(m.getId(), taken);
                continue;
            }
            int available = line.packs() - need;
            if (candidates.isEmpty()) {
                problems.add(m.getName() + " is not on any of your approved prescriptions.");
            } else if (available == 0) {
                problems.add("You've already ordered all the " + m.getName() + " your prescriptions allow.");
            } else {
                problems.add("Your prescriptions cover " + available + " more pack(s) of " + m.getName()
                        + ", and your cart has " + line.packs() + ".");
            }
        }
        return new Coverage(takes, problems);
    }

    /** Written for one of these patients. */
    private static boolean isFor(Prescription p, Set<String> patients) {
        if (p.getPatientName() == null || p.getPatientName().isBlank() || patients.isEmpty()) return false;
        return PatientNames.match(p.getPatientName(), new ArrayList<>(patients), n -> n).isPresent();
    }

    /** Records the packs an order line takes from its prescriptions, and who prescribed it, for the invoice. */
    public static void use(OrderItem line, List<Take> takes) {
        for (Take t : takes) {
            line.getPrescriptionUses().add(new PrescriptionUse(line, t.item(), t.packs()));
            t.item().setPacksOrdered(t.item().getPacksOrdered() + t.packs());
        }
        String by = takes.stream().map(t -> t.item().getPrescription()).distinct().map(p ->
                        (p.getDoctorName() == null || p.getDoctorName().isBlank() ? "" : "Dr. " + p.getDoctorName().trim())
                        + (p.getPrescribedOn() == null ? "" : ", " + p.getPrescribedOn().format(DAY)))
                .filter(s -> !s.isBlank()).collect(Collectors.joining("; "));
        line.setPrescribedBy(by.isEmpty() ? null : by.length() > 300 ? by.substring(0, 299) + "…" : by);
    }

    /** After an order: any prescription now fully ordered is used up, and the customer is told. */
    @Transactional
    public void finishUse(Collection<Prescription> used) {
        for (Prescription rx : used) {
            if (rx.getStatus() == PrescriptionStatus.APPROVED && rx.isFullyOrdered()) {
                rx.setStatus(PrescriptionStatus.USED_UP);
                notifications.notify(rx.getUser(), NotificationType.PRESCRIPTION_UPDATE, "Prescription used up",
                        "Everything on your prescription " + describe(rx) + " has now been ordered. Upload a new prescription "
                                + "to order prescription medicines again.", "/prescriptions");
            }
        }
    }

    /** A cancelled order gives its packs back to the prescriptions it used, which are usable again if they were used up. */
    @Transactional
    public void releaseUse(Order o) {
        Set<Prescription> touched = new LinkedHashSet<>();
        for (OrderItem oi : o.getItems()) {
            for (PrescriptionUse u : oi.getPrescriptionUses()) {
                PrescriptionItem i = u.getPrescriptionItem();
                i.setPacksOrdered(Math.max(0, i.getPacksOrdered() - u.getPacks()));
                touched.add(i.getPrescription());
            }
        }
        // Orders from before each line recorded its prescriptions: give back to the order's one prescription
        Prescription rx = o.getPrescription();
        if (touched.isEmpty() && rx != null && rx.isLimited()) {
            for (OrderItem oi : o.getItems()) {
                if (!oi.getMedicine().isPrescriptionRequired()) continue;
                rx.itemFor(oi.getMedicine().getId()).ifPresent(i -> i.setPacksOrdered(Math.max(0, i.getPacksOrdered() - oi.getQuantity())));
            }
            touched.add(rx);
        }
        touched.forEach(p -> {
            if (p.getStatus() == PrescriptionStatus.USED_UP && !p.isFullyOrdered()) p.setStatus(PrescriptionStatus.APPROVED);
        });
    }

    static String describe(Prescription p) {
        String doctor = p.getDoctorName() == null || p.getDoctorName().isBlank() ? "" : "from Dr. " + p.getDoctorName().trim() + " ";
        return doctor + "(uploaded " + p.getUploadedAt().format(DAY) + ")";
    }

    /** Owners and admins can view a file; anyone else gets "not found". */
    @Transactional(readOnly = true)
    public Prescription getForViewer(Long id, User viewer) {
        Prescription p = prescriptions.findById(id).orElseThrow(() -> new NotFoundException("File not found."));
        boolean owner = p.getUser().getId().equals(viewer.getId());
        // Staff only see what the customer actually sent; an unsent upload is the customer's alone
        boolean reviewer = (viewer.isAdmin() || viewer.is(Role.PHARMACIST)) && p.getStatus() != PrescriptionStatus.DRAFT;
        if (!reviewer && !owner) {
            throw new NotFoundException("File not found.");
        }
        return p;
    }

    public Resource file(Prescription p) { return storage.load(p.getStoredFileName()); }
}
