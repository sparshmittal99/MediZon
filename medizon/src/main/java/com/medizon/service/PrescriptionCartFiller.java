package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.PrescriptionRepository;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Hibernate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Once a pharmacist approves a prescription, its medicines go straight into the customer's cart (up to the packs
 * prescribed, minus any already there), priced for their default address, and the customer is told what was added.
 * Each medicine is added on its own, so one that's out of stock doesn't stop the rest or undo the approval.
 */
@Slf4j
@Service
public class PrescriptionCartFiller {

    private final PrescriptionRepository prescriptions;
    private final CartService cartService;
    private final AddressService addressService;
    private final NotificationService notifications;
    private final MemberService memberService;
    private final CartItemRepository cartItems;
    private final TransactionTemplate tx;

    public PrescriptionCartFiller(PrescriptionRepository prescriptions, CartService cartService, AddressService addressService,
                                  NotificationService notifications, MemberService memberService, CartItemRepository cartItems,
                                  PlatformTransactionManager txManager) {
        this.prescriptions = prescriptions;
        this.cartService = cartService;
        this.addressService = addressService;
        this.notifications = notifications;
        this.memberService = memberService;
        this.cartItems = cartItems;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    private record Wanted(Long medicineId, String name, int packs) {}

    /** What went into the cart, and why anything didn't. */
    public record Result(Prescription prescription, List<String> added, List<String> skipped) {}

    /** After the approval is saved. */
    @TransactionalEventListener
    public void onApproved(PrescriptionService.Approved e) {
        try {
            Result r = addRemainingToCart(e.prescriptionId());
            Prescription p = r.prescription();
            StringBuilder msg = new StringBuilder("Your prescription " + PrescriptionService.describe(p)
                    + " is approved and valid until " + p.getValidUntil() + ".");
            if (!r.added().isEmpty()) msg.append(" Added to your cart: ").append(String.join(", ", r.added())).append(".");
            if (!r.skipped().isEmpty()) msg.append(" Couldn't add: ").append(String.join(" ", r.skipped()));
            tx.executeWithoutResult(s -> notifications.notify(p.getUser(), NotificationType.PRESCRIPTION_UPDATE,
                    r.added().isEmpty() ? "Prescription approved" : "Prescription approved, medicines in your cart",
                    msg.toString(), r.added().isEmpty() ? "/prescriptions/" + p.getId() : "/cart"));
        } catch (RuntimeException ex) {
            log.error("Couldn't fill the cart from prescription {}", e.prescriptionId(), ex);
        }
    }

    /** Tops the cart up to the packs still left on the prescription. */
    public Result addRemainingToCart(Long prescriptionId) {
        Prescription p = tx.execute(s -> {
            Prescription q = prescriptions.findById(prescriptionId).orElseThrow();
            q.setUser((User) Hibernate.unproxy(q.getUser()));     // used after this transaction ends
            Hibernate.initialize(q.getItems());
            return q;
        });
        User user = p.getUser();
        List<Wanted> wanted = p.getItems().stream()
                .map(i -> new Wanted(i.getMedicine().getId(), i.getMedicine().getName(), i.getRemaining())).toList();
        String pin = tx.execute(s -> addressService.defaultFor(user).map(Address::getPincode).orElse(null));
        // The patient written on it decides whose medicines they are; nobody in the family matches: "Others"
        Member patient = tx.execute(s -> memberService.byPatientName(user, p.getPatientName()).orElseGet(() -> memberService.others(user)));
        // What's already in the cart for THIS patient: two prescriptions for two people can share medicines, and the
        // other person's packs mustn't count as this one's
        Map<Long, Integer> inCart = tx.execute(s -> {
            Map<Long, Integer> out = new HashMap<>();
            cartItems.findByUserOrderByIdAsc(user).forEach(c -> {
                int n = c.packsFor(patient.getId());
                if (patient.isOthers() && !hasPatient(c.getOthersPatientName(), p.getPatientName())) n = 0;  // someone else's
                if (n > 0) out.merge(c.getMedicine().getId(), n, Integer::sum);
            });
            return out;
        });

        List<String> added = new ArrayList<>(), skipped = new ArrayList<>();
        for (Wanted w : wanted) {
            int missing = w.packs() - inCart.getOrDefault(w.medicineId(), 0);
            if (w.packs() <= 0) continue;                  // all ordered already
            if (missing <= 0) { added.add(w.name() + " (already in your cart" + forWhom(patient, p.getPatientName()) + ")"); continue; }
            try {
                tx.executeWithoutResult(s -> {
                    cartService.add(user, w.medicineId(), missing, List.of(patient), pin);
                    if (patient.isOthers()) {
                        // Name who the "Others" packs are for; keep any other person's name already on the line
                        cartItems.findByUserOrderByIdAsc(user).stream().filter(c -> c.getMedicine().getId().equals(w.medicineId()))
                                .findFirst().ifPresent(c -> c.setOthersPatientName(withPatient(c.getOthersPatientName(), p.getPatientName())));
                    }
                });
                added.add(w.name() + " × " + missing + forWhom(patient, p.getPatientName()));
            } catch (BusinessException ex) {
                skipped.add(ex.getMessage());
            }
        }
        return new Result(p, added, skipped);
    }

    /** The "Others" names on a cart line ("Priya Verma, Ravi Kumar") include this patient. */
    static boolean hasPatient(String names, String patient) {
        if (names == null || names.isBlank() || patient == null || patient.isBlank()) return false;
        List<String> wanted = PatientNames.words(patient);
        return Arrays.stream(names.split(",")).anyMatch(n -> PatientNames.words(n).equals(wanted));
    }

    /** The "Others" names on a cart line with this patient added (once). */
    static String withPatient(String names, String patient) {
        if (patient == null || patient.isBlank()) return names;
        if (names == null || names.isBlank()) return patient;
        if (hasPatient(names, patient)) return names;
        String joined = names + ", " + patient;
        return joined.length() > 100 ? names : joined;
    }

    /** " for Mom", " for you" or " for Ravi Kumar (Others)". */
    private static String forWhom(Member m, String writtenName) {
        if (m.isSelf()) return " for you";
        if (m.isOthers()) return writtenName == null || writtenName.isBlank() ? " (Others)" : " for " + writtenName + " (Others)";
        return " for " + m.getLabel();
    }
}
