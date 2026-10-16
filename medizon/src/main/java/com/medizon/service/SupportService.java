package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.repository.SupportTicketRepository;
import com.medizon.repository.TicketMessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * Support tickets. A new ticket goes to the support team; support replies, or passes it to the specialist team that
 * can fix it (pharmacy, payments, the store that handled the order, or the admin), and the customer is told. That team
 * talks to the customer on the ticket and hands it back to support once solved; support closes it with a message, and
 * the customer can rate it or reopen it for {@value SupportTicket#REOPEN_DAYS} days. Support can always read a ticket
 * and add internal notes, and the admin can act on any ticket that isn't closed.
 *
 * Every change notifies whoever has to act next: the customer (in the app and by email) or the staff team.
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    public static final int MAX_IMAGES = 3;
    public static final int MAX_BODY = 2000;
    public static final int MAX_OPEN_PER_CUSTOMER = 5;
    public static final int MAX_NEW_PER_DAY = 10;
    /** Teams support can pass a ticket to. */
    public static final List<Role> TEAMS = List.of(Role.PHARMACIST, Role.FINANCE, Role.STORE, Role.ADMIN);
    public static final String CLOSING_MESSAGE = "We hope we were able to solve your issue. If anything still isn't right, "
            + "you can reopen this ticket within " + SupportTicket.REOPEN_DAYS + " days. Thank you for your patience.";

    private final SupportTicketRepository tickets;
    private final TicketMessageRepository messages;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;
    private final FileStorageService storage;

    // ------------------------------------------------------------------ who may do what

    /** Support and the admin see every ticket; a team sees the tickets passed to it (a store, only its own store's). */
    public static boolean canView(User staff, SupportTicket t) {
        if (staff.isAdmin() || staff.is(Role.SUPPORT)) return true;
        if (!staff.isStaff() || t.getTeam() == null || !staff.is(t.getTeam())) return false;
        if (staff.isStoreStaff()) {
            return staff.getStore() != null && t.getStore() != null && staff.getStore().getId().equals(t.getStore().getId());
        }
        return true;
    }

    /** Whose turn it is to answer the customer: support while it's with support, the team while it's with them. */
    public static boolean canReply(User staff, SupportTicket t) {
        if (t.getStatus().isClosed() || !canView(staff, t)) return false;
        if (staff.isAdmin()) return true;
        if (t.getStatus() == TicketStatus.WITH_TEAM) return staff.is(t.getTeam());
        return staff.is(Role.SUPPORT);
    }

    /** Internal notes: anyone who can see an open ticket, so support can keep watching after passing it on. */
    public static boolean canNote(User staff, SupportTicket t) {
        return !t.getStatus().isClosed() && canView(staff, t);
    }

    public static boolean canRedirect(User staff, SupportTicket t) {
        return (staff.isAdmin() || staff.is(Role.SUPPORT))
                && (t.getStatus() == TicketStatus.OPEN || t.getStatus() == TicketStatus.RESOLVED);
    }

    /** The team (or the admin) hands a solved ticket back to support. */
    public static boolean canHandBack(User staff, SupportTicket t) {
        return t.getStatus() == TicketStatus.WITH_TEAM && canView(staff, t) && (staff.isAdmin() || staff.is(t.getTeam()));
    }

    /** Support closes tickets it holds; the admin can close any ticket that's still open. */
    public static boolean canClose(User staff, SupportTicket t) {
        if (t.getStatus().isClosed()) return false;
        if (staff.isAdmin()) return true;
        return staff.is(Role.SUPPORT) && t.getStatus() != TicketStatus.WITH_TEAM;
    }

    /** The teams this ticket can go to: the store team only when it's about an order a store handled. */
    public static List<Role> teamsFor(SupportTicket t) {
        return TEAMS.stream().filter(r -> r != Role.STORE || t.getStore() != null).toList();
    }

    // ------------------------------------------------------------------ customer

    @Transactional(readOnly = true)
    public List<SupportTicket> forCustomer(User user) { return tickets.findByUserOrderByUpdatedAtDesc(user); }

    @Transactional(readOnly = true)
    public List<SupportTicket> forOrder(User user, Order order) { return tickets.findByUserAndOrderOrderByUpdatedAtDesc(user, order); }

    /** Another customer's ticket answers "not found", the same as one that doesn't exist. */
    @Transactional(readOnly = true)
    public SupportTicket getForCustomer(Long id, User user) {
        return tickets.findByIdAndUser(id, user).orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    @Transactional
    public SupportTicket create(User user, TicketTopic topic, String subject, String description, Long orderId,
                                List<MultipartFile> images) {
        if (!user.isCustomer()) throw new BusinessException("Support tickets are for customers.");
        if (topic == null) throw new BusinessException("Choose what your issue is about.");
        String s = text(subject, 120, "a short subject");
        String d = text(description, MAX_BODY, "what happened");
        if (tickets.countByUserAndStatusNot(user, TicketStatus.CLOSED) >= MAX_OPEN_PER_CUSTOMER) {
            throw new BusinessException("You have " + MAX_OPEN_PER_CUSTOMER + " open tickets already. Reply on one of those, "
                    + "or wait until they're closed.");
        }
        if (tickets.countCreatedSince(user, LocalDateTime.now().minusDays(1)) >= MAX_NEW_PER_DAY) {
            throw new BusinessException("You've raised a lot of tickets today. Reply on an existing one instead.");
        }
        List<MultipartFile> photos = photos(images);

        SupportTicket t = new SupportTicket();
        t.setUser(user);
        t.setTopic(topic);
        t.setSubject(s);
        if (orderId != null) {
            Order o = orderService.getForUser(orderId, user);
            t.setOrder(o);
            t.setStore(o.getStore());
        }
        t.addMessage(message(TicketMessageKind.CUSTOMER, user, d, photos));
        tickets.save(t);

        notifications.notify(user, NotificationType.SUPPORT_UPDATE, "We've got your request " + t.getNumber(),
                "Thanks for reaching out about \"" + s + "\". Our support team will reply here soon.", link(t));
        staffAlerts.notify(Role.SUPPORT, NotificationType.SUPPORT_TICKET, "New ticket " + t.getNumber() + ": " + s,
                user.getFullName() + " · " + topic.getLabel() + (t.getOrder() != null ? " · order " + t.getOrder().getOrderNumber() : ""),
                staffLink(t));
        return t;
    }

    @Transactional
    public void customerReply(Long id, User user, String body, List<MultipartFile> images) {
        SupportTicket t = getForCustomer(id, user);
        if (t.getStatus().isClosed()) throw new BusinessException("This ticket is closed. Reopen it to add a message.");
        t.addMessage(message(TicketMessageKind.CUSTOMER, user, text(body, MAX_BODY, "your message"), photos(images)));
        // Still not right after the team solved it: back to support
        if (t.getStatus() == TicketStatus.RESOLVED) t.setStatus(TicketStatus.OPEN);
        notifyHandlers(t, "New reply on " + t.getNumber(), user.getFullName() + " replied: " + preview(body));
    }

    @Transactional
    public void reopen(Long id, User user, String reason) {
        SupportTicket t = getForCustomer(id, user);
        if (!t.canReopen(LocalDateTime.now())) {
            throw new BusinessException("This ticket can't be reopened. Raise a new ticket and mention " + t.getNumber() + ".");
        }
        t.addMessage(message(TicketMessageKind.CUSTOMER, user, text(reason, MAX_BODY, "what's still wrong"), List.of()));
        t.addMessage(system("You reopened this ticket. Our support team will pick it up again."));
        t.setStatus(TicketStatus.OPEN);
        t.setClosedAt(null);
        t.setReopenCount(t.getReopenCount() + 1);
        t.setRating(null);
        t.setRatingComment(null);
        staffAlerts.notify(Role.SUPPORT, NotificationType.SUPPORT_TICKET, "Ticket reopened: " + t.getNumber(),
                user.getFullName() + ": " + preview(reason), staffLink(t));
    }

    @Transactional
    public void rate(Long id, User user, Integer stars, String comment) {
        SupportTicket t = getForCustomer(id, user);
        if (!t.canRate()) throw new BusinessException("You can rate a ticket once, after it's closed.");
        if (stars == null || stars < 1 || stars > 5) throw new BusinessException("Choose 1 to 5 stars.");
        t.setRating(stars);
        String c = comment == null ? "" : comment.trim();
        if (c.length() > 500) throw new BusinessException("Keep the comment under 500 characters.");
        t.setRatingComment(c.isEmpty() ? null : c);
    }

    // ------------------------------------------------------------------ staff

    /** One tab of the staff list: what's waiting for this person, what's with the teams, closed, or everything. */
    @Transactional(readOnly = true)
    public List<SupportTicket> staffList(User staff, String view, TicketTopic topic) {
        Predicate<SupportTicket> inView = viewFilter(staff, view);
        return tickets.findTop300ByOrderByUpdatedAtDesc().stream()
                .filter(t -> canView(staff, t))
                .filter(inView)
                .filter(t -> topic == null || t.getTopic() == topic)
                .toList();
    }

    @Transactional(readOnly = true)
    public Map<String, Long> staffCounts(User staff) {
        List<SupportTicket> visible = tickets.findTop300ByOrderByUpdatedAtDesc().stream().filter(t -> canView(staff, t)).toList();
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String v : List.of("queue", "teams", "closed", "all")) {
            Predicate<SupportTicket> p = viewFilter(staff, v);
            counts.put(v, visible.stream().filter(p).count());
        }
        return counts;
    }

    private static Predicate<SupportTicket> viewFilter(User staff, String view) {
        return switch (view == null ? "queue" : view) {
            case "teams" -> t -> t.getStatus() == TicketStatus.WITH_TEAM;
            case "closed" -> t -> t.getStatus().isClosed();
            case "all" -> t -> true;
            // Waiting for this person: support's open and solved tickets, a team's tickets with it, all open for the admin
            default -> staff.isAdmin() ? t -> !t.getStatus().isClosed()
                    : staff.is(Role.SUPPORT) ? t -> t.getStatus() == TicketStatus.OPEN || t.getStatus() == TicketStatus.RESOLVED
                    : t -> t.getStatus() == TicketStatus.WITH_TEAM;
        };
    }

    @Transactional(readOnly = true)
    public Double averageRating() { return tickets.averageRating(); }

    /** A ticket this staff account may see; anything else answers "not found". */
    @Transactional(readOnly = true)
    public SupportTicket getForStaff(Long id, User staff) {
        return tickets.findById(id).filter(t -> canView(staff, t)).orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    /** A reply to the customer, or with {@code internal} a note only staff see. */
    @Transactional
    public void staffReply(Long id, User staff, String body, List<MultipartFile> images, boolean internal) {
        SupportTicket t = getForStaff(id, staff);
        if (internal ? !canNote(staff, t) : !canReply(staff, t)) {
            throw new BusinessException(t.getStatus().isClosed() ? "This ticket is closed."
                    : t.getStatus() == TicketStatus.WITH_TEAM ? "The " + t.getTeamLabel().toLowerCase() + " is handling this ticket now. Add an internal note instead."
                    : "Support is handling this ticket now. Add an internal note instead.");
        }
        String b = text(body, MAX_BODY, internal ? "a note" : "your reply");
        t.addMessage(message(internal ? TicketMessageKind.NOTE : TicketMessageKind.STAFF, staff, b, photos(images)));
        if (!internal) {
            notifications.notify(t.getUser(), NotificationType.SUPPORT_UPDATE, "New reply on " + t.getNumber(),
                    staff.getFirstName() + " (" + SupportTicket.teamLabel(staff.getRole()) + "): " + preview(b), link(t));
        }
    }

    /** Passes the ticket to a specialist team; the customer is told, and the note goes to the team only. */
    @Transactional
    public void redirect(Long id, User staff, Role team, String note) {
        SupportTicket t = getForStaff(id, staff);
        if (!canRedirect(staff, t)) throw new BusinessException("Only support can pass on a ticket it's handling.");
        if (team == null || !teamsFor(t).contains(team)) throw new BusinessException("Choose a team for this ticket.");
        String n = text(note, MAX_BODY, "a note for the team about what's needed");
        t.setTeam(team);
        t.setStatus(TicketStatus.WITH_TEAM);
        t.addMessage(message(TicketMessageKind.NOTE, staff, "Passed to the " + SupportTicket.teamLabel(team).toLowerCase() + ": " + n, List.of()));
        String told = "We've passed your issue to our " + SupportTicket.teamLabel(team).toLowerCase()
                + ". They'll look into it and get back to you here soon.";
        t.addMessage(system(told));
        notifications.notify(t.getUser(), NotificationType.SUPPORT_UPDATE, t.getNumber() + " is with our " + SupportTicket.teamLabel(team).toLowerCase(),
                told, link(t));
        String title = "Ticket for your team: " + t.getNumber();
        String msg = t.getSubject() + " · " + preview(n);
        if (team == Role.STORE) staffAlerts.notifyStore(t.getStore(), true, NotificationType.SUPPORT_TICKET, title, msg, staffLink(t));
        else staffAlerts.notify(team, NotificationType.SUPPORT_TICKET, title, msg, staffLink(t));
    }

    /** The team has solved it: back to support, with what was done. */
    @Transactional
    public void handBack(Long id, User staff, String note) {
        SupportTicket t = getForStaff(id, staff);
        if (!canHandBack(staff, t)) throw new BusinessException("Only the team handling this ticket can hand it back.");
        String n = text(note, MAX_BODY, "what was done to solve it");
        t.setStatus(TicketStatus.RESOLVED);
        t.addMessage(message(TicketMessageKind.NOTE, staff, "Solved, back to support: " + n, List.of()));
        staffAlerts.notify(Role.SUPPORT, NotificationType.SUPPORT_TICKET, "Solved by the " + t.getTeamLabel().toLowerCase() + ": " + t.getNumber(),
                preview(n) + " Close it with the customer.", staffLink(t));
    }

    @Transactional
    public void close(Long id, User staff, String message) {
        SupportTicket t = getForStaff(id, staff);
        if (!canClose(staff, t)) {
            throw new BusinessException(t.getStatus() == TicketStatus.WITH_TEAM
                    ? "The " + t.getTeamLabel().toLowerCase() + " is still working on this ticket." : "This ticket is already closed.");
        }
        t.addMessage(message(TicketMessageKind.STAFF, staff, text(message, MAX_BODY, "a closing message"), List.of()));
        t.addMessage(system("Ticket closed. How did we do? Rate your experience below."));
        t.setStatus(TicketStatus.CLOSED);
        t.setClosedAt(LocalDateTime.now());
        notifications.notify(t.getUser(), NotificationType.SUPPORT_UPDATE, t.getNumber() + " is resolved",
                preview(message) + " Tell us how we did.", link(t));
    }

    // ------------------------------------------------------------------ photos

    /** A photo on a message, if the viewer may see the ticket (and, for the customer, the message); else "not found". */
    @Transactional(readOnly = true)
    public TicketAttachment attachment(Long messageId, int index, User viewer) {
        TicketMessage m = messages.findById(messageId).orElseThrow(() -> new NotFoundException("File not found."));
        SupportTicket t = m.getTicket();
        boolean allowed = viewer.isStaff() ? canView(viewer, t)
                : t.getUser().getId().equals(viewer.getId()) && m.isVisibleToCustomer();
        if (!allowed || index < 0 || index >= m.getAttachments().size()) throw new NotFoundException("File not found.");
        return m.getAttachments().get(index);
    }

    public Resource file(TicketAttachment a) { return storage.load(a.getStoredFileName()); }

    // ------------------------------------------------------------------ helpers

    private TicketMessage message(TicketMessageKind kind, User author, String body, List<MultipartFile> photos) {
        TicketMessage m = new TicketMessage();
        m.setKind(kind);
        m.setAuthor(author);
        m.setAuthorRole(author.getRole());
        m.setBody(body);
        photos.forEach(f -> m.getAttachments().add(new TicketAttachment(storage.storeSupportImage(f), f.getContentType())));
        return m;
    }

    private static TicketMessage system(String body) {
        TicketMessage m = new TicketMessage();
        m.setKind(TicketMessageKind.SYSTEM);
        m.setBody(body);
        return m;
    }

    /** Tells whoever is handling the ticket now: the team while it's with them, else support. */
    private void notifyHandlers(SupportTicket t, String title, String message) {
        if (t.getStatus() == TicketStatus.WITH_TEAM && t.getTeam() == Role.STORE) {
            staffAlerts.notifyStore(t.getStore(), true, NotificationType.SUPPORT_TICKET, title, message, staffLink(t));
        } else {
            staffAlerts.notify(t.getStatus() == TicketStatus.WITH_TEAM ? t.getTeam() : Role.SUPPORT,
                    NotificationType.SUPPORT_TICKET, title, message, staffLink(t));
        }
    }

    private static List<MultipartFile> photos(List<MultipartFile> images) {
        List<MultipartFile> p = images == null ? List.of() : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (p.size() > MAX_IMAGES) throw new BusinessException("Add at most " + MAX_IMAGES + " photos.");
        return p;
    }

    private static String text(String value, int max, String what) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) throw new BusinessException("Add " + what + ".");
        if (v.length() > max) throw new BusinessException("Keep it under " + max + " characters.");
        return v;
    }

    private static String preview(String s) {
        String v = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return v.length() <= 140 ? v : v.substring(0, 139) + "…";
    }

    private static String link(SupportTicket t) { return "/support/tickets/" + t.getId(); }

    private static String staffLink(SupportTicket t) { return "/admin/support/" + t.getId(); }
}
