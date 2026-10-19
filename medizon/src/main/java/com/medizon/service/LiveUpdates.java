package com.medizon.service;

import com.medizon.domain.*;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;
import jakarta.servlet.http.HttpServletRequest;
import org.hibernate.Hibernate;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.*;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Keeps open pages current without a manual refresh. Every record saved by a real action (a form, an API call, a
 * scheduled job) stamps whoever sees it: the customer it belongs to, all staff for back-office records, and everyone
 * when staff change the catalogue. The stamp goes out over the live connection; a page built before it reloads.
 * Writes made while a page is just being viewed (GET) don't count, so pages can't keep reloading each other.
 */
@Component
public class LiveUpdates implements PostInsertEventListener, PostUpdateEventListener, PostDeleteEventListener {

    /** Records the back office lists or reviews. */
    private static final Set<Class<?>> STAFF_RECORDS = Set.of(Order.class, OrderItem.class, Prescription.class,
            PrescriptionItem.class, ReturnRequest.class, ReturnItem.class, Review.class, SupportTicket.class,
            TicketMessage.class, User.class, Feedback.class, Medicine.class, Store.class, StoreItem.class,
            StoreArea.class, ServiceablePincode.class);

    /** What every shopper sees: changes made by staff (or a scheduled job) reach all signed-in pages. */
    private static final Set<Class<?>> CATALOGUE = Set.of(Medicine.class, Store.class, StoreItem.class,
            StoreArea.class, ServiceablePincode.class);

    private static final Object PENDING_KEY = new Object();

    private final EntityManagerFactory emf;
    private final NotificationPush push;

    // Stamps start from the clock, so pages built before a restart still see later changes as newer.
    private final AtomicLong clock = new AtomicLong();
    private final Map<Long, Long> userStamps = new ConcurrentHashMap<>();
    private final AtomicLong staffStamp = new AtomicLong();
    private final AtomicLong everyoneStamp = new AtomicLong();

    public LiveUpdates(EntityManagerFactory emf, NotificationPush push) {
        this.emf = emf;
        this.push = push;
    }

    @PostConstruct
    void register() {
        EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class)
                .getServiceRegistry().getService(EventListenerRegistry.class);
        registry.appendListeners(EventType.POST_INSERT, this);
        registry.appendListeners(EventType.POST_UPDATE, this);
        registry.appendListeners(EventType.POST_DELETE, this);
    }

    /** The newest change this user's pages show; a page built with an older one is out of date. */
    public long version(User user) {
        long v = Math.max(userStamps.getOrDefault(user.getId(), 0L), everyoneStamp.get());
        return user.isStaff() ? Math.max(v, staffStamp.get()) : v;
    }

    @Override public void onPostInsert(PostInsertEvent event) { record(event.getEntity()); }
    @Override public void onPostUpdate(PostUpdateEvent event) { record(event.getEntity()); }
    @Override public void onPostDelete(PostDeleteEvent event) { record(event.getEntity()); }
    @Override public boolean requiresPostCommitHandling(EntityPersister persister) { return false; }

    private void record(Object entity) {
        if (duringPageView()) return;
        Class<?> type = Hibernate.getClass(entity);
        Long owner = ownerId(entity);
        boolean staff = STAFF_RECORDS.contains(type);
        boolean everyone = CATALOGUE.contains(type) && !byCustomer();
        if (owner == null && !staff) return;

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            changed(owner == null ? Set.of() : Set.of(owner), staff, everyone);
            return;
        }
        // One signal per transaction, sent once it has committed (a rollback sends nothing)
        Pending pending = (Pending) TransactionSynchronizationManager.getResource(PENDING_KEY);
        if (pending == null) {
            Pending fresh = new Pending();
            TransactionSynchronizationManager.bindResource(PENDING_KEY, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(PENDING_KEY);
                    if (status == STATUS_COMMITTED) changed(fresh.users, fresh.staff, fresh.everyone);
                }
            });
            pending = fresh;
        }
        if (owner != null) pending.users.add(owner);
        pending.staff |= staff;
        pending.everyone |= everyone;
    }

    private void changed(Set<Long> users, boolean staff, boolean everyone) {
        long stamp = clock.updateAndGet(prev -> Math.max(prev + 1, System.currentTimeMillis()));
        users.forEach(id -> userStamps.merge(id, stamp, Math::max));
        if (staff) staffStamp.accumulateAndGet(stamp, Math::max);
        if (everyone) everyoneStamp.accumulateAndGet(stamp, Math::max);
        push.changed(users, staff, everyone, stamp);
    }

    /** The customer a record belongs to, following the links up to it (only through records already loaded). */
    private static Long ownerId(Object entity) {
        Object e = entity;
        for (int hop = 0; hop < 4 && e != null; hop++) {
            if (e instanceof User u) return u.getId();          // a proxy gives its id without loading
            if (!Hibernate.isInitialized(e)) return null;
            e = parent(e);
        }
        return null;
    }

    private static Object parent(Object e) {
        if (e instanceof Order x) return x.getUser();
        if (e instanceof OrderItem x) return x.getOrder();
        if (e instanceof Prescription x) return x.getUser();
        if (e instanceof PrescriptionItem x) return x.getPrescription();
        if (e instanceof PrescriptionUse x) return x.getOrderItem();
        if (e instanceof ReturnRequest x) return x.getUser();
        if (e instanceof ReturnItem x) return x.getRequest();
        if (e instanceof SupportTicket x) return x.getUser();
        if (e instanceof TicketMessage x) return x.getTicket();
        if (e instanceof Cart x) return x.getUser();
        if (e instanceof CartItem x) return x.getUser();
        if (e instanceof Address x) return x.getUser();
        if (e instanceof Member x) return x.getUser();
        if (e instanceof MedicationPlan x) return x.getUser();
        if (e instanceof DoseLog x) return x.getPlan();
        if (e instanceof Reminder x) return x.getUser();
        if (e instanceof Notification x) return x.getUser();
        if (e instanceof StockAlert x) return x.getUser();
        if (e instanceof Review x) return x.getUser();
        if (e instanceof Feedback x) return x.getUser();
        return null;   // device settings, visits, error logs, counters: nothing a page needs to redraw for
    }

    private static boolean duringPageView() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) return false;
        HttpServletRequest request = attrs.getRequest();
        return "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod());
    }

    private static boolean byCustomer() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_CUSTOMER".equals(a.getAuthority()));
    }

    private static class Pending {
        final Set<Long> users = new HashSet<>();
        boolean staff;
        boolean everyone;
    }
}
