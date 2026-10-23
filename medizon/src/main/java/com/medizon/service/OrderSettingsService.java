package com.medizon.service;

import com.medizon.domain.OrderSettings;
import com.medizon.repository.OrderSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** The admin's order settings (the minimum order value); the defaults until they're first saved. */
@Service
@RequiredArgsConstructor
public class OrderSettingsService {

    /** Highest minimum the admin can set, so a typo can't stop every order. */
    public static final BigDecimal MAX_MIN_ORDER_VALUE = new BigDecimal("10000");

    private final OrderSettingsRepository settings;

    public OrderSettings get() {
        return settings.findById(OrderSettings.ID).orElseGet(OrderSettings::defaults);
    }

    /** The smallest medicines total that can be checked out; zero when any amount can. */
    public BigDecimal minOrderValue() { return get().getMinOrderValue(); }

    @Transactional
    public OrderSettings save(BigDecimal minOrderValue, String by) {
        if (minOrderValue == null) throw new BusinessException("Enter the minimum order value, or 0 for none.");
        if (minOrderValue.signum() < 0 || minOrderValue.compareTo(MAX_MIN_ORDER_VALUE) > 0) {
            throw new BusinessException("The minimum order value must be ₹0 to ₹" + MAX_MIN_ORDER_VALUE.toPlainString() + ".");
        }
        OrderSettings s = get();
        s.setMinOrderValue(minOrderValue.setScale(2, RoundingMode.HALF_UP));
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(by);
        return settings.save(s);
    }

    /** What the medicines still need to reach {@code minimum}: zero once they do, or when there's no minimum. */
    public static BigDecimal shortBy(BigDecimal subtotal, BigDecimal minimum) {
        if (minimum == null || minimum.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal left = minimum.subtract(subtotal == null ? BigDecimal.ZERO : subtotal);
        return left.signum() > 0 ? left : BigDecimal.ZERO;
    }

    /** Stops a checkout whose medicines come to less than the minimum, saying how much more to add. */
    public void requireMinimum(BigDecimal subtotal) {
        BigDecimal minimum = minOrderValue();
        BigDecimal left = shortBy(subtotal, minimum);
        if (left.signum() > 0) {
            throw new BusinessException("The minimum order value is ₹" + plain(minimum) + ". Add medicines worth ₹" + plain(left)
                    + " more to place this order.");
        }
    }

    /** "99" rather than "99.00", "12.50" as it is. */
    public static String plain(BigDecimal amount) {
        BigDecimal a = amount.setScale(2, RoundingMode.HALF_UP);
        return a.stripTrailingZeros().scale() <= 0 ? a.setScale(0, RoundingMode.UNNECESSARY).toPlainString() : a.toPlainString();
    }
}
