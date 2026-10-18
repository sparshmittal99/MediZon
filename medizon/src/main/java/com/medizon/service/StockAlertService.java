package com.medizon.service;

import com.medizon.domain.Medicine;
import com.medizon.domain.NotificationType;
import com.medizon.domain.StockAlert;
import com.medizon.domain.User;
import com.medizon.repository.MedicineRepository;
import com.medizon.repository.StockAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.*;

/**
 * "Notify me when it's back": customers subscribe to an out-of-stock product and get one notification (bell, desktop
 * and email) once a store delivering to them has it again. Restocks are checked as soon as stock goes up, and every
 * few minutes for anything else that can bring a product back (a store reopening, a new delivery area).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockAlertService {

    private final StockAlertRepository alerts;
    private final MedicineRepository medicines;
    private final CatalogService catalog;
    private final NotificationService notifications;

    @Transactional
    public void subscribe(User user, Long medicineId, String pincode) {
        Medicine m = medicines.findById(medicineId).filter(Medicine::isActive)
                .orElseThrow(() -> new BusinessException("That product isn't sold any more."));
        String pin = DeliveryAreaService.normalize(pincode);
        if (catalog.offer(m, pin, 1).stock() > 0) throw new BusinessException(m.getName() + " is in stock. Add it to your cart.");
        StockAlert a = alerts.findByUserAndMedicineId(user, medicineId).orElseGet(() -> {
            StockAlert n = new StockAlert();
            n.setUser(user);
            n.setMedicine(m);
            return n;
        });
        a.setPincode(pin);      // the latest location they shopped for
        alerts.save(a);
    }

    @Transactional
    public void unsubscribe(User user, Long medicineId) {
        alerts.findByUserAndMedicineId(user, medicineId).ifPresent(alerts::delete);
    }

    @Transactional(readOnly = true)
    public Set<Long> subscribed(User user) { return new HashSet<>(alerts.medicineIds(user)); }

    /** Right after a restock commits. */
    @TransactionalEventListener(fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onStockChanged(StockChanged e) {
        try {
            check(List.of(e.medicineId()));
        } catch (RuntimeException ex) {
            log.warn("Back-in-stock check for medicine {} failed; the sweep will retry", e.medicineId(), ex);
        }
    }

    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    @Transactional
    public void sweep() {
        List<Long> ids = alerts.pendingMedicineIds();
        if (!ids.isEmpty()) check(ids);
    }

    /** Tells everyone waiting for these products whose location now has them, and drops their alerts. */
    void check(Collection<Long> medicineIds) {
        for (StockAlert a : alerts.findForMedicines(medicineIds)) {
            Medicine m = a.getMedicine();
            if (!m.isActive()) { alerts.delete(a); continue; }
            if (catalog.offer(m, a.getPincode(), 1).stock() <= 0) continue;
            notifications.notify(a.getUser(), NotificationType.BACK_IN_STOCK, m.getName() + " is back in stock",
                    m.getName() + " is available again. Order soon, before it runs out.", "/medicines/" + m.getId());
            alerts.delete(a);
        }
    }
}
