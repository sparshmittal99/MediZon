package com.medizon.service;

import com.medizon.domain.CartItem;
import com.medizon.domain.Medicine;
import com.medizon.domain.Member;
import com.medizon.domain.Shares;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.MedicineRepository;
import jakarta.servlet.http.HttpSession;
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
     * Adds packs, tagging them with who they're for. With no members given, a new line is for the account holder and
     * an existing line gives the packs to its first person; given members share the new packs evenly.
     */
    @Transactional
    public void add(User user, Long medicineId, int quantity, Collection<Member> forMembers) {
        if (user.isAdmin()) throw new BusinessException("Admin accounts can't shop.");
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        int qty = Math.max(1, quantity);
        CartItem item = lineFor(user, m);
        checkSellable(m, item.getQuantity() + qty);

        Map<Member, Integer> alloc = new LinkedHashMap<>(item.getAllocation());
        List<Member> to = forMembers.isEmpty()
                ? List.of(alloc.isEmpty() ? memberService.self(user) : alloc.keySet().iterator().next())
                : forMembers.stream().distinct().toList();
        int[] share = Shares.split(qty, new int[to.size()]);
        for (int k = 0; k < to.size(); k++) alloc.merge(to.get(k), share[k], Integer::sum);
        if (alloc.keySet().stream().anyMatch(Member::isOthers) && alloc.size() > 1) {
            alloc.keySet().removeIf(Member::isOthers);   // "Others" doesn't mix with the family
            int left = qty + item.getQuantity() - alloc.values().stream().mapToInt(Integer::intValue).sum();
            if (left > 0) alloc.merge(alloc.keySet().iterator().next(), left, Integer::sum);
        }
        item.allocate(alloc);
        cart.save(item);
    }

    /**
     * Sets how many packs of a medicine are in the cart; 0 removes it. Extra packs go to the line's first person;
     * fewer packs come off the last person first. Returns the new quantity.
     */
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
        checkSellable(m, quantity);
        CartItem item = lineFor(user, m);
        Map<Member, Integer> alloc = new LinkedHashMap<>(item.getAllocation());
        if (alloc.isEmpty()) alloc.put(memberService.self(user), 0);
        int diff = quantity - item.getQuantity();
        if (diff > 0) {
            alloc.merge(alloc.keySet().iterator().next(), diff, Integer::sum);
        } else {
            List<Member> fromLast = new ArrayList<>(alloc.keySet());
            Collections.reverse(fromLast);
            for (Member who : fromLast) {
                if (diff == 0) break;
                int take = Math.min(alloc.get(who), -diff);
                alloc.put(who, alloc.get(who) - take);
                diff += take;
            }
        }
        item.allocate(alloc);
        cart.save(item);
        return quantity;
    }

    /**
     * Sets how many packs each person gets: any mix of family members and the common (OTC) shelf, or "Others" (someone
     * outside the household) on its own. The line's quantity becomes the total. Prescription medicines are for a named
     * patient, so they can't go on the common shelf.
     */
    @Transactional
    public CartItem setAllocation(User user, Long medicineId, Map<Long, Integer> packsByMember) {
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        CartItem item = cart.findByUserAndMedicine(user, m)
                .orElseThrow(() -> new BusinessException(m.getName() + " isn't in your cart."));
        Map<Member, Integer> alloc = new LinkedHashMap<>();
        if (packsByMember != null) {
            packsByMember.forEach((id, n) -> {
                if (n != null && n > 0) alloc.put(memberService.get(id, user), n);
            });
        }
        if (alloc.isEmpty()) throw new BusinessException("Choose who " + m.getName() + " is for.");
        if (alloc.size() > 1 && alloc.keySet().stream().anyMatch(Member::isOthers)) {
            throw new BusinessException("\"Others\" is for someone outside your family, so it can't be combined with family members.");
        }
        if (m.isPrescriptionRequired() && alloc.keySet().stream().anyMatch(Member::isCommon)) {
            throw new BusinessException(m.getName() + " needs a prescription, so it has to be for a named person, not the common shelf.");
        }
        checkSellable(m, alloc.values().stream().mapToInt(Integer::intValue).sum());
        item.allocate(alloc);
        return cart.save(item);
    }

    private CartItem lineFor(User user, Medicine m) {
        return cart.findByUserAndMedicine(user, m).orElseGet(() -> {
            CartItem c = new CartItem();
            c.setUser(user);
            c.setMedicine(m);
            return c;
        });
    }

    /** Packs in the cart per medicine id, for the quantity steppers on product cards and pages. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> quantities(User user) {
        Map<Long, Integer> out = new HashMap<>();
        cart.findByUserOrderByIdAsc(user).forEach(i -> out.put(i.getMedicine().getId(), i.getQuantity()));
        return out;
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

    // ------------------------------------------------------------------ signed-out visitors

    /**
     * Visitors can fill a cart before signing in. It lives in their session as packs per medicine id and moves into
     * their account cart when they sign in (see {@link #mergeGuestCart}).
     */
    static final String GUEST_CART = "mzGuestCart";

    @SuppressWarnings("unchecked")
    private static Map<Long, Integer> guest(HttpSession session, boolean create) {
        if (session == null) return new LinkedHashMap<>();
        Object cartInSession = session.getAttribute(GUEST_CART);
        if (cartInSession instanceof Map<?, ?> m) return (Map<Long, Integer>) m;
        Map<Long, Integer> fresh = new LinkedHashMap<>();
        if (create) session.setAttribute(GUEST_CART, fresh);
        return fresh;
    }

    /** Sets how many packs of a medicine are in a visitor's cart; 0 removes it. Returns the new quantity. */
    @Transactional(readOnly = true)
    public int setGuestQuantity(HttpSession session, Long medicineId, int quantity) {
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        Map<Long, Integer> items = guest(session, true);
        if (quantity <= 0) {
            items.remove(medicineId);
        } else {
            checkSellable(m, quantity);
            items.put(medicineId, quantity);
        }
        session.setAttribute(GUEST_CART, items);   // re-set so clustered sessions see the change
        return Math.max(0, quantity);
    }

    @Transactional(readOnly = true)
    public void addGuest(HttpSession session, Long medicineId, int quantity) {
        int now = guest(session, false).getOrDefault(medicineId, 0);
        setGuestQuantity(session, medicineId, now + Math.max(1, quantity));
    }

    public Map<Long, Integer> guestQuantities(HttpSession session) {
        return new HashMap<>(guest(session, false));
    }

    public long guestCount(HttpSession session) { return guest(session, false).size(); }

    /** Whether there's a visitor cart waiting to move into an account; cheap, so pages can check on every request. */
    public static boolean hasGuestCart(HttpSession session) {
        return session != null && session.getAttribute(GUEST_CART) != null;
    }

    /** The visitor's cart priced like an account cart. Lines aren't saved, so they have no id and no members yet. */
    @Transactional(readOnly = true)
    public CartSummary guestSummary(HttpSession session) {
        List<CartItem> items = new ArrayList<>();
        guest(session, false).forEach((id, qty) -> medicines.findById(id).filter(Medicine::isActive).ifPresent(m -> {
            CartItem c = new CartItem();
            c.setMedicine(m);
            c.setQuantity(qty);
            items.add(c);
        }));
        BigDecimal subtotal = items.stream().map(CartItem::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = deliveryFeeFor(subtotal);
        boolean rx = items.stream().anyMatch(i -> i.getMedicine().isPrescriptionRequired());
        return new CartSummary(items, subtotal, fee, subtotal.add(fee), rx);
    }

    /**
     * Moves a visitor's cart into the account they just signed in to. Packs add to what the account cart already
     * has, up to the stock; medicines that can no longer be sold are skipped. Admin accounts just drop it.
     */
    @Transactional
    public void mergeGuestCart(User user, HttpSession session) {
        if (!hasGuestCart(session)) return;
        Map<Long, Integer> items = guest(session, false);
        session.removeAttribute(GUEST_CART);
        if (user.isAdmin()) return;
        items.forEach((id, qty) -> medicines.findById(id).ifPresent(m -> {
            int already = cart.findByUserAndMedicine(user, m).map(CartItem::getQuantity).orElse(0);
            int target = Math.min(m.getStock(), already + qty);
            if (target <= already) return;
            try {
                setQuantity(user, id, target);
            } catch (BusinessException ignored) {
                // no longer sold or expired since it was added: leave it out
            }
        }));
    }

    private static void checkSellable(Medicine m, int quantity) {
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        if (m.isExpired()) throw new BusinessException(m.getName() + " is past its expiry date and can't be sold right now.");
        if (quantity > m.getStock()) {
            throw new BusinessException(m.getStock() == 0
                    ? m.getName() + " is out of stock."
                    : "Only " + m.getStock() + " packs of " + m.getName() + " are in stock.");
        }
    }
}
