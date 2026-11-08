package com.medizon.service;

import com.medizon.domain.OrderSettings;
import com.medizon.domain.PaymentMethod;
import com.medizon.repository.OrderSettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;

/** The admin's order settings (minimum order value, payment method charges); the defaults until they're first saved. */
@Service
@RequiredArgsConstructor
public class OrderSettingsService {

    /** Highest minimum the admin can set, so a typo can't stop every order. */
    public static final BigDecimal MAX_MIN_ORDER_VALUE = new BigDecimal("10000");
    /** Highest charge for a way to pay, so a typo can't make paying that way absurdly expensive. */
    public static final BigDecimal MAX_PAYMENT_FEE = new BigDecimal("500");
    public static final BigDecimal MAX_GST_RATE = new BigDecimal("28");

    /**
     * What paying a certain way adds: {@code amount} includes GST at {@code gstRate} percent.
     * {@link #NONE} when that way is free.
     */
    public record PaymentCharge(BigDecimal amount, BigDecimal gstRate) {
        public static final PaymentCharge NONE = new PaymentCharge(BigDecimal.ZERO, BigDecimal.ZERO);
        public boolean isFree() { return amount.signum() == 0; }
    }

    private final OrderSettingsRepository settings;

    public OrderSettings get() {
        return settings.findById(OrderSettings.ID).orElseGet(OrderSettings::defaults);
    }

    /** The smallest medicines total that can be checked out; zero when any amount can. */
    public BigDecimal minOrderValue() { return get().getMinOrderValue(); }

    /** The admin's charge for paying this way, before GST; zero when it's free (and for MediZon Money). */
    public BigDecimal paymentFeeBase(PaymentMethod method) {
        if (method == null) return BigDecimal.ZERO;
        OrderSettings s = get();
        BigDecimal fee = switch (method) {
            case UPI -> s.getUpiFee();
            case CARD, CREDIT_CARD, DEBIT_CARD -> s.getCardFee();
            case NETBANKING -> s.getNetbankingFee();
            case COD, UPI_ON_DELIVERY -> s.getCodFee();
            case WALLET -> BigDecimal.ZERO;
        };
        return fee == null ? BigDecimal.ZERO : fee;
    }

    /** What paying this way adds to the bill, GST included: ₹10 at 18% is ₹11.80. */
    public PaymentCharge paymentCharge(PaymentMethod method) {
        BigDecimal base = paymentFeeBase(method);
        if (base.signum() <= 0) return PaymentCharge.NONE;
        BigDecimal rate = get().getPaymentFeeGstRate() == null ? BigDecimal.ZERO : get().getPaymentFeeGstRate();
        BigDecimal amount = base.add(base.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                .setScale(2, RoundingMode.HALF_UP);
        return new PaymentCharge(amount, rate);
    }

    /** "Free", "₹10 + GST", or "₹10" when there's no GST on it: as the payment page shows it beside the method. */
    public String paymentFeeLabel(PaymentMethod method) {
        BigDecimal base = paymentFeeBase(method);
        if (base.signum() <= 0) return "Free";
        BigDecimal rate = get().getPaymentFeeGstRate();
        return "₹" + plain(base) + (rate != null && rate.signum() > 0 ? " + GST" : "");
    }

    /** Saves each way to pay's charge (before GST) and the GST rate on them. */
    @Transactional
    public OrderSettings savePaymentFees(BigDecimal upi, BigDecimal card, BigDecimal netbanking, BigDecimal cod,
                                         BigDecimal gstRate, String by) {
        OrderSettings s = get();
        s.setUpiFee(fee(upi, "UPI"));
        s.setCardFee(fee(card, "card"));
        s.setNetbankingFee(fee(netbanking, "net banking"));
        s.setCodFee(fee(cod, "pay on delivery"));
        if (gstRate == null || gstRate.signum() < 0 || gstRate.compareTo(MAX_GST_RATE) > 0) {
            throw new BusinessException("GST on payment charges must be 0% to " + MAX_GST_RATE.toPlainString() + "%.");
        }
        s.setPaymentFeeGstRate(gstRate.setScale(2, RoundingMode.HALF_UP));
        s.setUpdatedAt(LocalDateTime.now());
        s.setUpdatedBy(by);
        return settings.save(s);
    }

    private static BigDecimal fee(BigDecimal fee, String what) {
        if (fee == null) throw new BusinessException("Enter the charge for " + what + ", or 0 for free.");
        if (fee.signum() < 0 || fee.compareTo(MAX_PAYMENT_FEE) > 0) {
            throw new BusinessException("The charge for " + what + " must be ₹0 to ₹" + MAX_PAYMENT_FEE.toPlainString() + ".");
        }
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

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
