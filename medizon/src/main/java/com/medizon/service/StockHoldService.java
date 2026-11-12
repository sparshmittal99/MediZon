package com.medizon.service;

import com.medizon.domain.CartItem;
import com.medizon.domain.CheckoutPayment;
import com.medizon.domain.StockHold;
import com.medizon.domain.StoreItem;
import com.medizon.dto.CartSummary;
import com.medizon.repository.CheckoutPaymentRepository;
import com.medizon.repository.StockHoldRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Sets stock aside while a customer pays online, as big shops do: when the payment opens, the packs in the cart are held
 * for {@code medizon.payment.hold-minutes} so nobody else can buy the last ones meanwhile. Placing the order turns the
 * hold into the sale; a refunded or closed payment ends it at once, and an abandoned one simply runs out, putting the
 * packs back on sale.
 *
 * <p>Holds are rows, not database locks: a stock row is locked only for the moment it's checked and changed, as before.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockHoldService {

    private final StockHoldRepository holds;
    private final CheckoutPaymentRepository payments;
    private final EntityManager em;

    /** How long packs stay held while the customer pays. */
    @Value("${medizon.payment.hold-minutes:10}")
    private int holdMinutes;

    /**
     * Saves payment {@code p} and holds the cart's packs for it, or throws, saving nothing, if another customer got
     * there first. Any earlier hold of the customer's ends: only the payment they're making now holds stock.
     *
     * @throws BusinessException naming what can no longer be bought
     */
    @Transactional
    public void open(CheckoutPayment p, CartSummary cart) {
        Long userId = p.getUser().getId();
        holds.deleteByUser(userId);
        Map<Long, Integer> wanted = new LinkedHashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (CartItem i : cart.getItems()) {
            if (!i.isCounted()) continue;
            wanted.merge(i.getOffer().item().getId(), i.getQuantity(), Integer::sum);
            names.put(i.getOffer().item().getId(), i.getMedicine().getName());
        }
        Map<Long, StoreItem> locked = lock(wanted.keySet());
        Map<Long, Integer> others = heldByOthers(wanted.keySet(), userId);
        wanted.forEach((id, qty) -> {
            StoreItem si = locked.get(id);
            if (si == null) throw new BusinessException(names.get(id) + " is no longer listed. Remove it from your cart.");
            si.setHeld(others.getOrDefault(id, 0));
            if (!si.isSellable() || si.getAvailable() < qty) throw new BusinessException(shortBy(si, names.get(id)));
        });
        payments.save(p);
        LocalDateTime until = LocalDateTime.now().plusMinutes(holdMinutes);
        wanted.forEach((id, qty) -> holds.save(new StockHold(id, userId, p.getId(), qty, until)));
    }

    /** Why a listing can't supply what was wanted. Packs held for others count as gone; who holds them isn't said. */
    static String shortBy(StoreItem si, String name) {
        int left = si.isSellable() ? Math.max(0, si.getAvailable()) : 0;
        return left == 0 ? name + " is out of stock right now." : "Only " + left + " packs of " + name + " are left.";
    }

    /**
     * Locks these listings' rows until the transaction ends, lowest id first so two checkouts can't deadlock, and
     * re-reads each so its stock is the one in the database now, not an earlier copy. By id.
     */
    @Transactional
    public Map<Long, StoreItem> lock(Collection<Long> storeItemIds) {
        Map<Long, StoreItem> out = new HashMap<>();
        for (Long id : new TreeSet<>(storeItemIds)) {
            StoreItem si = em.find(StoreItem.class, id);
            if (si == null) continue;
            em.refresh(si, LockModeType.PESSIMISTIC_WRITE);
            out.put(id, si);
        }
        return out;
    }

    /** Packs of each listing held, right now, for customers other than {@code userId} (null: anyone). By listing id. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> heldByOthers(Collection<Long> storeItemIds, Long userId) {
        Map<Long, Integer> out = new HashMap<>();
        if (storeItemIds.isEmpty()) return out;
        for (StockHold h : holds.findActive(storeItemIds, LocalDateTime.now())) {
            if (!h.getUserId().equals(userId)) out.merge(h.getStoreItemId(), h.getQuantity(), Integer::sum);
        }
        return out;
    }

    /** Ends payment {@code paymentId}'s hold: it became orders, was refunded or was closed. */
    public void release(Long paymentId) {
        holds.deleteByPayment(paymentId);
    }

    /** Ends every hold of the customer's: they've just placed their order. */
    public void releaseFor(Long userId) {
        holds.deleteByUser(userId);
    }

    /** Clears out holds that have run out; they stopped counting when they did. */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT5M")
    public void deleteExpired() {
        try {
            int n = holds.deleteExpired(LocalDateTime.now());
            if (n > 0) log.debug("Deleted {} expired stock holds", n);
        } catch (Exception e) {
            log.warn("Couldn't delete expired stock holds: {}", e.getMessage());
        }
    }
}
