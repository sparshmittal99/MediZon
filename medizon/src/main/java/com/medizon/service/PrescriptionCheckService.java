package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.OrderRepository;
import com.medizon.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Orders placed with prescription medicines no approved prescription covered ({@link RxCheck}). Until their
 * prescription is approved only the admin can confirm them; rejected, they're cancelled and the customer is told why.
 * <ul>
 *   <li>Uploaded at checkout: the pharmacist reviews it like any upload; approving it covers the order's medicines
 *       (instead of filling the cart), rejecting it cancels the order.</li>
 *   <li>No prescription: a consultation ticket goes to the pharmacists. A pharmacist (or the admin) starts a chat with
 *       the customer, then either issues a digital prescription, with how to take each medicine, which is saved with
 *       the customer's prescriptions, or rejects it, which cancels the order. The chat stays on the ticket.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PrescriptionCheckService {

    static final String UPLOAD_REJECTED = "Our pharmacist couldn't approve your prescription, so this order has been cancelled.";
    static final String CONSULT_REJECTED = "Our pharmacist couldn't approve a prescription for this order, so it has been cancelled.";

    /** Doses a pharmacist can pick for morning, afternoon and night. */
    public static final List<String> DOSES = List.of("0", "½", "1", "1½", "2");
    /** When to take it. */
    public static final List<String> FOOD = List.of("After food", "Before food", "With food", "Any time");
    public static final int MAX_DAYS = 365;

    private final OrderRepository orders;
    private final OrderService orderService;
    private final PrescriptionService prescriptionService;
    private final SupportService supportService;
    private final SupportTicketRepository tickets;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;
    private final PrescriptionPdfService pdf;

    // ------------------------------------------------------------------ uploaded at checkout

    /** Approved: each order waiting only for its uploads now takes its medicines from them, and can be confirmed. */
    @EventListener
    @Transactional
    public void onApproved(PrescriptionService.Approved e) {
        for (Order o : orders.findAwaiting(e.prescriptionId(), OrderStatus.CANCELLED)) {
            if (o.getRxReview() != RxReview.PENDING) continue;
            boolean waiting = o.getAwaitedPrescriptions().stream().anyMatch(p -> p.getStatus() == PrescriptionStatus.PENDING);
            if (waiting) continue;                   // another file sent for it is still with the pharmacist
            cover(o, o.getAwaitedPrescriptions());
            approved(o);
            notifications.notify(o.getUser(), NotificationType.ORDER_UPDATE, "Prescription approved",
                    "Your prescription for order " + o.getOrderNumber() + " is approved. The store will confirm your order shortly."
                            + o.getAwaitedPrescriptions().stream().map(Prescription::getReviewerNote)
                                    .filter(n -> n != null && !n.isBlank()).map(n -> " Pharmacist's note: " + n.trim())
                                    .distinct().collect(java.util.stream.Collectors.joining()),
                    o.getCustomerPath());
        }
    }

    /** Rejected: every order placed with it is cancelled, saying so. */
    @EventListener
    @Transactional
    public void onRejected(PrescriptionService.Rejected e) {
        String reason = UPLOAD_REJECTED + (e.note() == null || e.note().isBlank() ? "" : " Pharmacist's note: " + e.note().trim());
        for (Order o : orders.findAwaiting(e.prescriptionId(), OrderStatus.CANCELLED)) {
            if (o.getRxReview() == RxReview.PENDING) reject(o, reason);
        }
    }

    /** The order's uncovered prescription medicines taken from these prescriptions, as far as they cover them. */
    private void cover(Order o, Collection<Prescription> from) {
        List<OrderItem> open = o.getItems().stream().filter(OrderService::uncoveredPacks).toList();
        List<PrescriptionService.RxLine> lines = open.stream()
                .map(i -> new PrescriptionService.RxLine(i.getMedicine(), i.getQuantity() - usedPacks(i), patients(i))).toList();
        PrescriptionService.Coverage c = PrescriptionService.coverFrom(from, lines);
        for (OrderItem i : open) {
            List<PrescriptionService.Take> takes = c.forMedicine(i.getMedicine().getId());
            if (!takes.isEmpty()) PrescriptionService.use(i, takes);
        }
        if (o.getPrescription() == null) {
            o.getPrescriptionsUsed().stream().findFirst().ifPresent(rx -> {
                o.setPrescription(rx);
                o.setDoctorName(rx.isDigital() ? null : rx.getDoctorName());
                o.setPrescriptionDate(rx.getPrescribedOn());
            });
        }
        prescriptionService.finishUse(c.prescriptions());
    }

    private void approved(Order o) {
        o.setRxReview(RxReview.APPROVED);
        o.setUpdatedAt(LocalDateTime.now());
        staffAlerts.notifyStore(o.getStore(), false, NotificationType.ORDER_UPDATE, "Prescription approved: " + o.getOrderNumber(),
                "Order " + o.getOrderNumber() + " is ready to confirm.", "/admin/orders/" + o.getId());
    }

    /** Cancels the order for its prescription, through the usual cancellation (refund, restock, emails). */
    private void reject(Order o, String reason) {
        o.setRxReview(RxReview.REJECTED);
        o.setCancelReason(reason.length() > 300 ? reason.substring(0, 299) + "…" : reason);
        if (o.isCancellable()) {
            orderService.updateStatus(o.getId(), OrderStatus.CANCELLED);
        } else {
            // Already on its way (the admin confirmed it without the prescription): the admin decides what to do
            staffAlerts.notify(Role.ADMIN, NotificationType.ORDER_UPDATE, "Prescription rejected: " + o.getOrderNumber(),
                    "Order " + o.getOrderNumber() + " is " + o.getStatus().getLabel().toLowerCase() + " and can't be cancelled.",
                    "/admin/orders/" + o.getId());
        }
    }

    private static int usedPacks(OrderItem i) {
        return i.getPrescriptionUses().stream().mapToInt(PrescriptionUse::getPacks).sum();
    }

    private static Set<String> patients(OrderItem i) {
        Set<String> out = new LinkedHashSet<>();
        if (i.getPatientName() != null) {
            for (String n : i.getPatientName().split(",")) if (!n.isBlank()) out.add(n.trim());
        }
        return out;
    }

    // ------------------------------------------------------------------ no prescription: the consultation

    /** Orders placed with "No prescription": one consultation for the checkout, with the pharmacists. */
    @EventListener
    @Transactional
    public void onAwaiting(OrderService.AwaitingPrescription e) {
        if (e.rxCheck() != RxCheck.CONSULT) return;
        List<Order> placed = orders.findAllById(e.orderIds()).stream().sorted(Comparator.comparing(Order::getId)).toList();
        if (placed.isEmpty()) return;
        Order first = placed.get(0);
        SupportTicket t = new SupportTicket();
        t.setUser(first.getUser());
        t.setOrder(first);
        t.setStore(first.getStore());
        t.setTopic(TicketTopic.PRESCRIPTION);
        String subject = "Prescription for order " + numbers(placed);
        t.setSubject(subject.length() > 120 ? subject.substring(0, 119) + "…" : subject);
        t.setStatus(TicketStatus.WITH_TEAM);
        t.setTeam(Role.PHARMACIST);
        t.setConsult(true);
        t.setConsultReview(RxReview.PENDING);
        t.addMessage(SupportService.system("Our pharmacist will reach you shortly and provide you a digital prescription."));
        tickets.save(t);
        staffAlerts.notify(Role.PHARMACIST, NotificationType.SUPPORT_TICKET, "Prescription needed: " + numbers(placed),
                first.getUser().getFullName() + " ordered prescription medicines without a prescription. Chat with them to issue one.",
                "/admin/support/" + t.getId());
    }

    /** The orders of a consultation: its order and any placed with it in the same checkout. */
    @Transactional(readOnly = true)
    public List<Order> consultOrders(SupportTicket t) {
        if (!t.isConsult() || t.getOrder() == null) return List.of();
        List<Order> out = new ArrayList<>();
        out.add(t.getOrder());
        out.addAll(orderService.placedWith(t.getOrder()));
        return out.stream().filter(o -> o.getRxCheck() == RxCheck.CONSULT).sorted(Comparator.comparing(Order::getId)).toList();
    }

    /** Those still waiting for the prescription. */
    private List<Order> openOrders(SupportTicket t) {
        return consultOrders(t).stream().filter(Order::isAwaitingPrescription).toList();
    }

    /** A prescription medicine to prescribe: the packs ordered (across the checkout's orders) and who it's for. */
    public record RxItem(Medicine medicine, int packs, String patient) {}

    /** The prescription medicines the consultation is about, one row per medicine. */
    @Transactional(readOnly = true)
    public List<RxItem> rxItems(SupportTicket t) {
        Map<Long, RxItem> out = new LinkedHashMap<>();
        for (Order o : openOrders(t)) {
            for (OrderItem i : o.getItems()) {
                if (!OrderService.uncoveredPacks(i)) continue;
                int n = i.getQuantity() - usedPacks(i);
                out.merge(i.getMedicine().getId(), new RxItem(i.getMedicine(), n, i.getPatientName()),
                        (a, b) -> new RxItem(a.medicine(), a.packs() + b.packs(), a.patient() != null ? a.patient() : b.patient()));
            }
        }
        return new ArrayList<>(out.values());
    }

    /** The patient to prefill: the first named on the order's prescription medicines, else the account holder. */
    public String patientFor(SupportTicket t, List<RxItem> items) {
        return items.stream().map(RxItem::patient).filter(n -> n != null && !n.isBlank())
                .map(n -> n.split(",")[0].trim()).findFirst().orElse(t.getUser().getFullName());
    }

    /**
     * The pharmacist or admin starts the chat: the first message introduces them and lists the prescription medicines,
     * and the customer is told.
     */
    @Transactional
    public void startChat(Long ticketId, User staff) {
        SupportTicket t = consultFor(ticketId, staff);
        if (t.isChatStarted()) return;
        List<Order> open = openOrders(t);
        if (open.isEmpty()) throw new BusinessException("This order is cancelled.");
        String rx = rxItems(t).stream().map(i -> "• " + i.medicine().getName() + " × " + i.packs())
                .collect(Collectors.joining("\n"));
        String body = "Hello, I am MediZon pharmacist. This chat is regarding your order " + numbers(open) + "."
                + (rx.isEmpty() ? "" : "\n\nPrescription medicines in this order:\n" + rx);
        TicketMessage m = new TicketMessage();
        m.setKind(TicketMessageKind.STAFF);
        m.setAuthor(staff);
        m.setAuthorRole(Role.PHARMACIST);           // the admin, too, talks to the customer as the pharmacist
        m.setBody(body.length() > SupportService.MAX_BODY ? body.substring(0, SupportService.MAX_BODY - 1) + "…" : body);
        t.addMessage(m);
        t.setChatStartedAt(LocalDateTime.now());
        notifications.notifyAs(EmailService.Sender.PHARMACIST, t.getUser(), NotificationType.SUPPORT_UPDATE, "MediZon pharmacist",
                "Our pharmacist has started a chat about your order " + numbers(open) + ".", "/support/tickets/" + t.getId());
    }

    /** How to take one medicine, as the pharmacist chose it on the prescription screen. */
    public record Dose(Long medicineId, String morning, String noon, String night, String food, Integer days) {}

    /**
     * Approves: builds the digital prescription from the doses, saves it with the customer's prescriptions, takes the
     * orders' medicines from it, and closes the consultation. Once only.
     */
    @Transactional
    public Prescription approve(Long ticketId, User staff, String patientName, String advice, List<Dose> doses) {
        SupportTicket t = consultFor(ticketId, staff);
        if (!t.isChatStarted()) throw new BusinessException("Start the chat with the customer first.");
        List<Order> open = openOrders(t);
        if (open.isEmpty()) throw new BusinessException("This order is cancelled.");
        String patient = patientName == null ? "" : patientName.trim();
        if (patient.isEmpty() || patient.length() > 100) throw new BusinessException("Enter the patient's name (up to 100 characters).");
        String note = advice == null ? "" : advice.trim();
        if (note.length() > 1000) throw new BusinessException("Keep the advice under 1000 characters.");

        Map<Long, Dose> byMedicine = new HashMap<>();
        if (doses != null) doses.forEach(d -> byMedicine.put(d.medicineId(), d));
        List<PrescriptionService.DigitalLine> lines = new ArrayList<>();
        for (RxItem i : rxItems(t)) {
            Dose d = byMedicine.get(i.medicine().getId());
            String name = i.medicine().getName();
            if (d == null) throw new BusinessException("Choose how to take " + name + ".");
            List<String> parts = List.of(dose(d.morning()), dose(d.noon()), dose(d.night()));
            if (parts.stream().allMatch("0"::equals)) throw new BusinessException("Choose at least one dose of " + name + ".");
            if (d.food() == null || !FOOD.contains(d.food())) throw new BusinessException("Choose when to take " + name + ".");
            if (d.days() == null || d.days() < 1 || d.days() > MAX_DAYS) {
                throw new BusinessException("Enter for how many days to take " + name + " (1 to " + MAX_DAYS + ").");
            }
            lines.add(new PrescriptionService.DigitalLine(i.medicine(), i.packs(), String.join("-", parts),
                    d.food() + " · " + d.days() + (d.days() == 1 ? " day" : " days")));
        }

        // Customers only ever see a pharmacist: the pharmacist's name, or just "MediZon pharmacist" when the admin issued it
        String issuer = staff.is(Role.PHARMACIST) ? staff.getFullName() : null;
        byte[] file = pdf.render(new PrescriptionPdfService.Sheet(patient, t.getUser().getFullName(), issuer,
                java.time.LocalDate.now(), numbers(open), lines, note));
        Prescription rx = prescriptionService.issueDigital(t.getUser(), patient, issuer, note, lines, file);
        t.setConsultReview(RxReview.APPROVED);
        t.setConsultPrescription(rx);
        for (Order o : open) {
            cover(o, List.of(rx));
            approved(o);
        }
        t.addMessage(SupportService.system("Your digital prescription is ready and saved in your prescriptions."));
        close(t);
        notifications.notify(t.getUser(), NotificationType.PRESCRIPTION_UPDATE, "Your digital prescription is ready",
                "Our pharmacist has issued your prescription for order " + numbers(open) + ". The store will confirm your order shortly.",
                "/prescriptions/" + rx.getId());
        return rx;
    }

    /** Rejects: the orders are cancelled, saying why, and the consultation closes. Once only. */
    @Transactional
    public void reject(Long ticketId, User staff) {
        SupportTicket t = consultFor(ticketId, staff);
        if (!t.isChatStarted()) throw new BusinessException("Start the chat with the customer first.");
        List<Order> open = openOrders(t);
        t.setConsultReview(RxReview.REJECTED);          // before cancelling, so the cancellation doesn't close it as "cancelled"
        t.addMessage(SupportService.system(open.size() > 1
                ? "Our pharmacist couldn't approve a prescription for these orders, so they have been cancelled."
                : CONSULT_REJECTED));
        close(t);
        open.forEach(o -> reject(o, CONSULT_REJECTED));
    }

    /** The customer (or anyone) cancelled every order of an open consultation: there's nothing left to prescribe. */
    @EventListener
    @Transactional
    public void onStatusChanged(OrderEmailService.StatusChanged e) {
        if (e.status() != OrderStatus.CANCELLED) return;
        Order o = orders.findById(e.orderId()).orElse(null);
        if (o == null || o.getRxCheck() != RxCheck.CONSULT) return;
        List<Order> group = new ArrayList<>(List.of(o));
        group.addAll(orderService.placedWith(o));
        for (SupportTicket t : tickets.findByOrderInAndConsultTrue(group)) {
            if (!t.isConsultOpen() || t.getStatus().isClosed() || !openOrders(t).isEmpty()) continue;
            t.addMessage(SupportService.system("This order was cancelled."));
            close(t);
        }
    }

    private SupportTicket consultFor(Long ticketId, User staff) {
        SupportTicket t = supportService.getForStaff(ticketId, staff);
        if (!SupportService.canConsult(staff, t)) {
            throw new BusinessException(t.isConsult() && !t.isConsultOpen() ? "This prescription has already been decided."
                    : "Only a pharmacist or the admin can do this.");
        }
        return t;
    }

    private static void close(SupportTicket t) {
        t.setStatus(TicketStatus.CLOSED);
        t.setClosedAt(LocalDateTime.now());
    }

    private static String dose(String v) {
        String d = v == null ? "0" : v.trim();
        if (!DOSES.contains(d)) throw new BusinessException("Choose a dose from the list.");
        return d;
    }

    private static String numbers(List<Order> list) {
        return list.stream().map(Order::getOrderNumber).collect(Collectors.joining(", "));
    }
}
