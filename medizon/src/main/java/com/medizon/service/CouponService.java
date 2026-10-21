package com.medizon.service;

import com.medizon.domain.Coupon;
import com.medizon.domain.OrderStatus;
import com.medizon.domain.User;
import com.medizon.repository.CouponRepository;
import com.medizon.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Promo codes at checkout. Each code works once a day per customer; cancelled orders give the day's use back. */
@Service
@RequiredArgsConstructor
public class CouponService {

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

    public Optional<Coupon> find(String code) {
        String c = normalize(code);
        return c == null ? Optional.empty() : coupons.findByCodeIgnoreCase(c);
    }

    public boolean usedToday(User user, String code) {
        return user != null && user.getId() != null
                && orders.existsByUserAndCouponCodeIgnoreCaseAndCreatedAtAfterAndStatusNot(user, code,
                        LocalDate.now().atStartOfDay(), OrderStatus.CANCELLED);
    }

    /** Checks {@code code} for medicines worth {@code subtotal}; null when there's no code. */
    public Applied evaluate(User user, String code, BigDecimal subtotal) {
        if (normalize(code) == null) return null;
        Coupon c = find(code).filter(Coupon::isLive).orElse(null);
        if (c == null) return new Applied(null, BigDecimal.ZERO, "That promo code isn't valid.");
        if (usedToday(user, c.getCode())) {
            return new Applied(c, BigDecimal.ZERO, c.getCode() + " is already used today. Each promo works once a day.");
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

    static String normalize(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase(Locale.ROOT);
        return c.isEmpty() || c.length() > 20 ? null : c;
    }
}
