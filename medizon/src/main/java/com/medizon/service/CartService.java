package com.medizon.service;

import com.medizon.domain.Cart;
import com.medizon.domain.CartItem;
import com.medizon.domain.CartStatus;
import com.medizon.domain.Medicine;
import com.medizon.domain.Member;
import com.medizon.domain.Shares;
import com.medizon.domain.User;
import com.medizon.dto.CartSummary;
import com.medizon.dto.Offer;
import com.medizon.repository.CartItemRepository;
import com.medizon.repository.CartRepository;
import com.medizon.repository.MedicineRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The cart. What can go in it, and at what price, depends on where it's going: {@code pincode} is the shopper's
 * delivery location (null when they haven't picked one, which allows anything some store has in stock). Checkout
 * checks every line again against the delivery address.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    public static final BigDecimal FREE_DELIVERY_ABOVE = new BigDecimal("499");
    public static final BigDecimal DELIVERY_FEE = new BigDecimal("40");
    /**
     * Most packs of one medicine in a cart. Also keeps quantities far from int overflow, which would otherwise wrap a
     * huge request to a negative number that passes the stock check.
     */
    public static final int MAX_PACKS_PER_LINE = 50;

    private final CartItemRepository cart;
    private final CartRepository carts;
    private final MedicineRepository medicines;
    private final MemberService memberService;
    private final CatalogService catalog;
    private final CouponService coupons;

    public static BigDecimal deliveryFeeFor(BigDecimal subtotal) {
        if (subtotal.signum() == 0 || subtotal.compareTo(FREE_DELIVERY_ABOVE) >= 0) return BigDecimal.ZERO;
        return DELIVERY_FEE;
    }

    @Transactional
    public void add(User user, Long medicineId, int quantity, String pincode) {
        add(user, medicineId, quantity, List.of(), pincode);
    }

    /**
     * Adds packs, tagging them with who they're for. With no members given, a new line is for the account holder and
     * an existing line gives the packs to its first person; given members share the new packs evenly.
     */
    @Transactional
    public void add(User user, Long medicineId, int quantity, Collection<Member> forMembers, String pincode) {
        if (user.isStaff()) throw new BusinessException("Staff accounts can't shop.");
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        int qty = Math.max(1, Math.min(quantity, MAX_PACKS_PER_LINE));
        CartItem item = lineFor(user, m);
        checkSellable(m, withinLimit(m, (long) item.getQuantity() + qty), pincode);

        Map<Member, Integer> alloc = new LinkedHashMap<>(item.getAllocation());
        List<Member> to = forMembers.isEmpty()
                ? List.of(alloc.isEmpty() ? memberService.self(user) : alloc.keySet().iterator().next())
                : forMembers.stream().distinct().toList();
        int[] share = Shares.split(qty, new int[to.size()]);
        // "Others" can share a line with family members (two prescriptions, one medicine): each keeps their own packs
        for (int k = 0; k < to.size(); k++) alloc.merge(to.get(k), share[k], Integer::sum);
        item.allocate(alloc);
        cart.save(item);
    }

    /**
     * Sets how many packs of a medicine are in the cart; 0 removes it. Extra packs go to the line's first person;
     * fewer packs come off the last person first. Returns the new quantity.
     */
    @Transactional
    public int setQuantity(User user, Long medicineId, int quantity, String pincode) {
        if (user.isStaff()) throw new BusinessException("Staff accounts can't shop.");
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        Optional<CartItem> existing = cart.findByUserAndMedicine(user, m);
        if (quantity <= 0) {
            existing.ifPresent(cart::delete);
            return 0;
        }
        checkSellable(m, withinLimit(m, quantity), pincode);
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
    public CartItem setAllocation(User user, Long medicineId, Map<Long, Integer> packsByMember, String pincode) {
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
        if (m.isPrescriptionRequired() && alloc.keySet().stream().anyMatch(Member::isCommon)) {
            throw new BusinessException(m.getName() + " needs a prescription, so it has to be for a named person, not the common shelf.");
        }
        checkSellable(m, withinLimit(m, alloc.values().stream().mapToLong(Integer::longValue).sum()), pincode);
        item.allocate(alloc);
        return cart.save(item);
    }

    private CartItem lineFor(User user, Medicine m) {
        return cart.findByUserAndMedicine(user, m).orElseGet(() -> {
            CartItem c = new CartItem();
            c.setUser(user);
            c.setCart(openCart(user));
            c.setMedicine(m);
            return c;
        });
    }

    // ------------------------------------------------------------------ cart ids

    /** The account's open cart, if it has one: none before the first medicine goes in, or right after checkout. */
    @Transactional(readOnly = true)
    public Optional<Cart> activeCart(User user) {
        return carts.findFirstByUserAndStatusOrderByIdAsc(user, CartStatus.ACTIVE);
    }

    private Cart openCart(User user) {
        return activeCart(user).orElseGet(() -> carts.save(new Cart(user)));
    }

    /**
     * Checkout: closes the cart and empties it; the next medicine added opens a new cart with a new id. Returns the
     * closed cart, for the orders to point back to.
     */
    @Transactional
    public Cart checkOut(User user) {
        Cart closed = openCart(user);
        carts.findByUserAndStatus(user, CartStatus.ACTIVE).forEach(c -> {   // normally just the one
            c.setStatus(CartStatus.CHECKED_OUT);
            c.setCheckedOutAt(LocalDateTime.now());
        });
        cart.deleteByUser(user);
        return closed;
    }

    /** Cart lines from before carts had ids go into their account's open cart. */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void assignLegacyLinesToCarts() {
        List<CartItem> legacy = cart.findByCartIsNull();
        if (legacy.isEmpty()) return;
        legacy.stream().collect(Collectors.groupingBy(CartItem::getUser)).forEach((user, lines) -> {
            Cart c = openCart(user);
            lines.forEach(l -> l.setCart(c));
        });
        log.info("Put {} cart line(s) into carts", legacy.size());
    }

    /** Packs in the cart per medicine id, for the quantity steppers on product cards and pages. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> quantities(User user) {
        Map<Long, Integer> out = new HashMap<>();
        cart.findByUserOrderByIdAsc(user).forEach(i -> out.put(i.getMedicine().getId(), i.getQuantity()));
        return out;
    }

    /** The cart priced for delivery to {@code pincode}: each line by the store that would deliver it. */
    @Transactional(readOnly = true)
    public CartSummary summary(User user, String pincode) {
        return price(cart.findByUserOrderByIdAsc(user), pincode, null, null);
    }

    /** As {@link #summary(User, String)}, with the promo {@code couponCode} checked and taken off when it applies. */
    @Transactional(readOnly = true)
    public CartSummary summary(User user, String pincode, String couponCode) {
        return price(cart.findByUserOrderByIdAsc(user), pincode, user, couponCode);
    }

    /** Works out each line's store and price; lines nobody here can supply don't count towards the totals. */
    private CartSummary price(List<CartItem> items, String pincode, User user, String couponCode) {
        Map<Medicine, Integer> wanted = new LinkedHashMap<>();
        items.forEach(i -> wanted.put(i.getMedicine(), i.getQuantity()));
        Map<Long, Offer> offers = catalog.offers(wanted, pincode);
        items.forEach(i -> i.setOffer(offers.get(i.getMedicine().getId())));
        BigDecimal subtotal = items.stream().filter(CartItem::isAvailable).map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fee = deliveryFeeFor(subtotal);
        boolean rx = items.stream().anyMatch(i -> i.getMedicine().isPrescriptionRequired());
        return new CartSummary(items, subtotal, fee, rx, couponCode == null ? null : coupons.evaluate(user, couponCode, subtotal));
    }

    public long count(User user) { return cart.countByUser(user); }

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
    public int setGuestQuantity(HttpSession session, Long medicineId, int quantity, String pincode) {
        Medicine m = medicines.findById(medicineId)
                .orElseThrow(() -> new BusinessException("That medicine doesn't exist."));
        Map<Long, Integer> items = guest(session, true);
        if (quantity <= 0) {
            items.remove(medicineId);
        } else {
            checkSellable(m, withinLimit(m, quantity), pincode);
            items.put(medicineId, quantity);
        }
        session.setAttribute(GUEST_CART, items);   // re-set so clustered sessions see the change
        return Math.max(0, quantity);
    }

    @Transactional(readOnly = true)
    public void addGuest(HttpSession session, Long medicineId, int quantity, String pincode) {
        int now = guest(session, false).getOrDefault(medicineId, 0);
        long total = (long) now + Math.max(1, Math.min(quantity, MAX_PACKS_PER_LINE));
        setGuestQuantity(session, medicineId, (int) Math.min(total, MAX_PACKS_PER_LINE + 1L), pincode);
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
    public CartSummary guestSummary(HttpSession session, String pincode) {
        List<CartItem> items = new ArrayList<>();
        guest(session, false).forEach((id, qty) -> medicines.findById(id).filter(Medicine::isActive).ifPresent(m -> {
            CartItem c = new CartItem();
            c.setMedicine(m);
            c.setQuantity(qty);
            items.add(c);
        }));
        return price(items, pincode, null, null);
    }

    /**
     * Moves a visitor's cart into the account they just signed in to. Packs add to what the account cart already
     * has; medicines that are no longer sold are skipped, and the cart page flags any that no store can supply.
     * Staff accounts just drop it.
     */
    @Transactional
    public void mergeGuestCart(User user, HttpSession session) {
        if (!hasGuestCart(session)) return;
        Map<Long, Integer> items = guest(session, false);
        session.removeAttribute(GUEST_CART);
        if (user.isStaff()) return;
        items.forEach((id, qty) -> medicines.findById(id).filter(Medicine::isActive).ifPresent(m -> {
            if (qty == null || qty <= 0) return;
            CartItem item = lineFor(user, m);
            int add = Math.min(qty, MAX_PACKS_PER_LINE - item.getQuantity());     // the account cart may already be full
            if (add <= 0) return;
            Map<Member, Integer> alloc = new LinkedHashMap<>(item.getAllocation());
            if (alloc.isEmpty()) alloc.put(memberService.self(user), 0);
            alloc.merge(alloc.keySet().iterator().next(), add, Integer::sum);
            item.allocate(alloc);
            cart.save(item);
        }));
    }

    /** The packs, if within {@link #MAX_PACKS_PER_LINE}. Takes a long so a sum can't overflow before it's checked. */
    private static int withinLimit(Medicine m, long packs) {
        if (packs > MAX_PACKS_PER_LINE) {
            throw new BusinessException("You can buy at most " + MAX_PACKS_PER_LINE + " packs of " + m.getName() + " at a time.");
        }
        return (int) packs;
    }

    /** Throws unless a store that delivers to {@code pincode} (any store, without one) can supply these packs. */
    private void checkSellable(Medicine m, int quantity, String pincode) {
        if (!m.isActive()) throw new BusinessException(m.getName() + " is no longer sold.");
        Offer o = catalog.offer(m, pincode, quantity);
        if (o.canSell(quantity)) return;
        String where = pincode == null ? "" : " for delivery to " + pincode;
        if (!o.isListed()) throw new BusinessException(m.getName() + " isn't sold" + where + ".");
        if (o.stock() == 0) throw new BusinessException(m.getName() + " is out of stock" + where + ".");
        throw new BusinessException("Only " + o.stock() + " packs of " + m.getName() + " are available" + where + ".");
    }
}
