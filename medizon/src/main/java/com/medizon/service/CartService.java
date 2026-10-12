package com.medizon.service;

import com.medizon.domain.CartItem;
import com.medizon.domain.Medicine;
import com.medizon.domain.Member;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.*;

@Service
@RequiredArgsConstructor
public class CartService {

    public static final BigDecimal FREE_DELIVERY_ABOVE = new BigDecimal("499");
    public static final BigDecimal DELIVERY_FEE = new BigDecimal("40");

    private final CartItemRepository cart;
    private final MedicineRepository medicines;
    private final MemberService memberService;

    public static BigDecimal deliveryFeeFor(BigDecimal subtotal) {
        if (subtotal.signum() == 0 || subtotal.compareTo(FREE_DELIVERY_ABOVE) >= 0) return BigDecimal.ZERO;
        return DELIVERY_FEE;
    }

    @Transactional
    public void add(User user, Long medicineId, int quantity) {
        add(user, medicineId, quantity, List.of());
    }

    /**
     * Adds packs, tagging the line with who it's for. A new line with no members given is for the account holder;
     * an existing line keeps its members and gains the given ones.
     */
    @Transactional
    public void add(User user, Long medicineId, int quantity, Collection<Member> forMembers) {
        if (user.isAdmin()) throw new BusinessException("Admin accounts can't shop.");
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        if (m.isExpired()) throw new BusinessException(m.getName() + " is past its expiry date and can't be sold right now.");
        int qty = Math.max(1, quantity);
        CartItem item = cart.findByUserAndMedicine(user, m).orElseGet(() -> {
            CartItem c = new CartItem();
            c.setUser(user);
            c.setMedicine(m);
            return c;
        });
        int newQty = item.getQuantity() + qty;
        if (newQty > m.getStock()) {
            throw new BusinessException(m.getStock() == 0
                    ? m.getName() + " is out of stock."
                    : "Only " + m.getStock() + " packs of " + m.getName() + " are in stock.");
        }
        item.setQuantity(newQty);
        item.getMembers().addAll(forMembers);
        if (item.getMembers().isEmpty()) item.getMembers().add(memberService.self(user));
        cart.save(item);
    }

    /** Sets how many packs of a medicine are in the cart; 0 removes it. Returns the new quantity. */
    @Transactional
    public int setQuantity(User user, Long medicineId, int quantity) {
        if (user.isAdmin()) throw new BusinessException("Admin accounts can't shop.");
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        Optional<CartItem> existing = cart.findByUserAndMedicine(user, m);
        if (quantity <= 0) {
            existing.ifPresent(cart::delete);
            return 0;
        }
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        if (m.isExpired()) throw new BusinessException(m.getName() + " is past its expiry date and can't be sold right now.");
        if (quantity > m.getStock()) {
            throw new BusinessException(m.getStock() == 0
                    ? m.getName() + " is out of stock."
                    : "Only " + m.getStock() + " packs of " + m.getName() + " are in stock.");
        }
        CartItem item = existing.orElseGet(() -> {
            CartItem c = new CartItem();
            c.setUser(user);
            c.setMedicine(m);
            return c;
        });
        item.setQuantity(quantity);
        if (item.getMembers().isEmpty()) item.getMembers().add(memberService.self(user));
        cart.save(item);
        return quantity;
    }

    /**
     * Sets who a cart line is for: any mix of family members, or the common (OTC) shelf. Prescription medicines are
     * for a named patient, so they can't go on the common shelf.
     */
    @Transactional
    public CartItem setMembers(User user, Long medicineId, Collection<Long> memberIds) {
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        CartItem item = cart.findByUserAndMedicine(user, m)
                .orElseThrow(() -> new BusinessException(m.getName() + " isn't in your cart."));
        Set<Member> chosen = new HashSet<>();
        if (memberIds != null) memberIds.forEach(id -> chosen.add(memberService.get(id, user)));
        if (chosen.isEmpty()) throw new BusinessException("Choose who " + m.getName() + " is for.");
        if (m.isPrescriptionRequired() && chosen.stream().anyMatch(Member::isCommon)) {
            throw new BusinessException(m.getName() + " needs a prescription, so it has to be for a named person, not the common shelf.");
        }
        item.getMembers().clear();
        item.getMembers().addAll(chosen);
        return item;
    }

    /** Packs in the cart per medicine id, for the quantity steppers on product cards and pages. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> quantities(User user) {
        Map<Long, Integer> out = new HashMap<>();
        cart.findByUserOrderByIdAsc(user).forEach(i -> out.put(i.getMedicine().getId(), i.getQuantity()));
        return out;
    }

    @Transactional
    public void remove(User user, Long itemId) {
        cart.findByIdAndUser(itemId, user).ifPresent(cart::delete);
    }

    @Transactional(readOnly = true)
    public CartSummary summary(User user) {
        List<CartItem> items = cart.findByUserOrderByIdAsc(user);
        BigDecimal subtotal = items.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = deliveryFeeFor(subtotal);
        boolean rx = items.stream().anyMatch(i -> i.getMedicine().isPrescriptionRequired());
        return new CartSummary(items, subtotal, fee, subtotal.add(fee), rx);
    }

    public long count(User user) { return cart.countByUser(user); }

    @Transactional
    public void clear(User user) { cart.deleteByUser(user); }
}
