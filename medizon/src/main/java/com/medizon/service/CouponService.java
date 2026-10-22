package com.medizon.service;

import com.medizon.domain.Coupon;
import com.medizon.domain.OrderStatus;
import com.medizon.domain.User;
import com.medizon.dto.PromoForm;
import com.medizon.repository.CouponRepository;
import com.medizon.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Promo codes: checked at checkout, managed by the admin. Each code works a set number of times a day per customer;
 * cancelled orders give the day's use back.
 */
@Service
@RequiredArgsConstructor
public class CouponService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9]{3,20}$");

    private final CouponRepository coupons;
    private final OrderRepository orders;

    /** A promo checked against a cart: the rupees off, or why it doesn't apply (then {@code discount} is zero). */
    public record Applied(Coupon coupon, BigDecimal discount, String problem) {
        public boolean isOk() { return problem == null; }
    }

    /** Promos on offer, smallest minimum order first, for the cards at checkout. */
    public List<Coupon> available() {
        return coupons.findByActiveTrueOrderByMinOrderAscIdAsc().stream().filter(Coupon::isLive).toList();
    }

    /**
     * The promos on offer ordered for a cart of medicines worth {@code subtotal}: those that work on it first, the
     * biggest saving at the top; then those it doesn't qualify for yet or that are used up today, nearest to working first.
     */
    public static List<Coupon> bestFirst(List<Coupon> promos, BigDecimal subtotal, Set<String> usedToday) {
        Predicate<Coupon> works = c -> !usedToday.contains(c.getCode()) && subtotal.compareTo(c.getMinOrder()) >= 0;
        return promos.stream().sorted(Comparator.comparing((Coupon c) -> !works.test(c))
                        .thenComparing(c -> works.test(c) ? c.discountOn(subtotal).negate() : BigDecimal.ZERO)
                        .thenComparing(c -> usedToday.contains(c.getCode()))
                        .thenComparing(Coupon::getMinOrder))
                .toList();
    }

    public Optional<Coupon> find(String code) {
        String c = normalize(code);
        return c == null ? Optional.empty() : coupons.findByCodeIgnoreCase(c);
    }

    public long usesToday(User user, String code) {
        if (user == null || user.getId() == null) return 0;
        return orders.promoUses(user, code, LocalDate.now().atStartOfDay().minusNanos(1), OrderStatus.CANCELLED);
    }

    /** Has the customer used up today's uses of this promo? */
    public boolean usedToday(User user, Coupon c) {
        return usesToday(user, c.getCode()) >= c.getUsesPerDay();
    }

    /** Checks {@code code} for medicines worth {@code subtotal}; null when there's no code. */
    public Applied evaluate(User user, String code, BigDecimal subtotal) {
        if (normalize(code) == null) return null;
        Coupon c = find(code).filter(Coupon::isLive).orElse(null);
        if (c == null) return new Applied(null, BigDecimal.ZERO, "That promo code isn't valid.");
        if (usedToday(user, c)) {
            return new Applied(c, BigDecimal.ZERO, c.getUsesPerDay() == 1
                    ? c.getCode() + " is already used today. It works once a day."
                    : c.getCode() + " is already used " + c.getUsesPerDay() + " times today, its daily limit.");
        }
        if (subtotal.compareTo(c.getMinOrder()) < 0) {
            return new Applied(c, BigDecimal.ZERO, "Add medicines worth ₹" + c.getMinOrder().subtract(subtotal).setScale(2)
                    + " more to use " + c.getCode() + ".");
        }
        return new Applied(c, c.discountOn(subtotal), null);
    }

    /** Throws unless the code gives a discount on this cart. */
    public Applied require(User user, String code, BigDecimal subtotal) {
        Applied a = evaluate(user, code, subtotal);
        if (a != null && !a.isOk()) throw new BusinessException(a.problem());
        return a;
    }

    // ------------------------------------------------------------------ admin

    /** Every promo, live ones first. */
    public List<Coupon> all() { return coupons.findAllByOrderByActiveDescCreatedAtDesc(); }

    public Coupon get(Long id) {
        return coupons.findById(id).orElseThrow(() -> new NotFoundException("Promo not found."));
    }

    /** Uses and rupees given, by code (in capitals). */
    public record Usage(long uses, BigDecimal given) {}

    public Map<String, Usage> usage() {
        Map<String, Usage> out = new HashMap<>();
        for (Object[] row : orders.promoTotals(OrderStatus.CANCELLED)) {
            out.put((String) row[0], new Usage(((Number) row[1]).longValue(), (BigDecimal) row[2]));
        }
        return out;
    }

    /** Adds a promo ({@code form.id} null) or changes one. A code that has been used keeps its code. */
    @Transactional
    public Coupon save(PromoForm form) {
        String code = normalize(form.getCode());
        if (code == null || !CODE.matcher(code).matches()) {
            throw new BusinessException("The code needs 3 to 20 letters or digits, no spaces, e.g. HEALTH10.");
        }
        Coupon c = form.getId() == null ? new Coupon() : get(form.getId());
        if (form.getId() == null ? coupons.existsByCodeIgnoreCase(code) : coupons.existsByCodeIgnoreCaseAndIdNot(code, form.getId())) {
            throw new BusinessException("There's already a promo called " + code + ".");
        }
        if (c.getId() != null && !c.getCode().equalsIgnoreCase(code) && usage().containsKey(c.getCode().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(c.getCode() + " has been used on orders, so its code can't change. Switch it off and add a new one.");
        }
        if (form.getKind() == null) throw new BusinessException("Choose a percent or a flat discount.");
        BigDecimal amount = form.getAmount();
        if (amount == null || amount.signum() <= 0) throw new BusinessException("Enter the discount.");
        if (form.getKind() == Coupon.Kind.PERCENT && amount.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new BusinessException("A percent discount can be at most 100%.");
        }
        if (form.getMaxDiscount() != null && form.getMaxDiscount().signum() <= 0) throw new BusinessException("The cap must be more than ₹0, or left empty.");
        BigDecimal min = form.getMinOrder() == null ? BigDecimal.ZERO : form.getMinOrder();
        if (min.signum() < 0) throw new BusinessException("The minimum order can't be negative.");
        if (form.getUsesPerDay() < 1 || form.getUsesPerDay() > 10) throw new BusinessException("Uses per customer a day must be 1 to 10.");
        String title = form.getTitle() == null ? "" : form.getTitle().trim();
        String description = form.getDescription() == null ? "" : form.getDescription().trim();
        if (title.length() > 80) throw new BusinessException("Keep the title under 80 characters.");
        if (description.length() > 200) throw new BusinessException("Keep the description under 200 characters.");

        c.setCode(code);
        c.setKind(form.getKind());
        c.setAmount(amount);
        c.setMaxDiscount(form.getKind() == Coupon.Kind.PERCENT ? form.getMaxDiscount() : null);
        c.setMinOrder(min);
        c.setUsesPerDay(form.getUsesPerDay());
        c.setValidUntil(form.getValidUntil());
        c.setActive(form.isActive());
        c.setTitle(title.isEmpty() ? c.describe() : title);
        c.setDescription(!description.isEmpty() ? description
                : min.signum() > 0 ? "On medicines worth ₹" + min.stripTrailingZeros().toPlainString() + " or more." : "On any order.");
        return coupons.save(c);
    }

    @Transactional
    public Coupon setActive(Long id, boolean active) {
        Coupon c = get(id);
        c.setActive(active);
        return c;
    }

    static String normalize(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.isEmpty() || c.length() > 20 ? null : c;
    }
}
