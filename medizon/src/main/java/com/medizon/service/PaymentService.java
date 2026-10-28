package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.payment.Gateway;
import com.medizon.payment.MediZonUpiGateway;
import com.medizon.payment.PaymentGateway;
import com.medizon.payment.PaymentGateways;
import com.medizon.payment.RazorpayGateway;
import com.medizon.repository.CheckoutPaymentRepository;
import com.medizon.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Paying for the cart on the payment page. Online methods go through the gateway that takes them (Razorpay for cards
 * and net banking, MediZon UPI for UPI; see {@link PaymentGateways}) and the orders are placed only once the payment is
 * confirmed with it. Pay on delivery and MediZon Money place them straight away.
 *
 * <p>Money is never kept without an order: a payment whose orders can't be placed (the cart changed, stock ran out)
 * is refunded at once, and {@link #settleLeftovers()} refunds any payment left unconfirmed, e.g. when the browser was
 * closed right after paying.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentGateways gateways;
    private final RazorpayGateway razorpay;
    private final MediZonUpiGateway upi;
    private final CheckoutPaymentRepository payments;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final NotificationService notifications;

    /** Minutes a payment may stay unconfirmed before it's checked with its gateway and refunded if it went through. */
    @Value("${medizon.payment.settle-minutes:20}")
    private int settleMinutes;

    /** The online methods some gateway takes, for the payment page. */
    public List<PaymentMethod> onlineMethods() { return gateways.onlineMethods(); }

    /** UPI is paid through MediZon UPI (test mode), not a gateway account. */
    public boolean isUpiTestMode() {
        return gateways.forMethod(PaymentMethod.UPI).map(g -> g.id() == Gateway.MEDIZON_UPI).orElse(false);
    }

    /**
     * What the browser opens: Razorpay Checkout with {@code key} and {@code orderId} ({@code method} is Razorpay's name
     * for the method), or MediZon UPI's checkout for {@code orderId}, open {@code expiresIn} seconds.
     */
    public record Opened(Gateway gateway, String orderId, long amount, String label, String method, String key, long expiresIn) {}

    /** Orders placed: where to go next, their numbers and how they were paid, for the "Order placed" screen. */
    public record Placed(String redirect, List<String> orderNumbers, String paid) {}

    /** Starts paying the cart online: a gateway order for what MediZon Money doesn't cover. Nothing is placed yet. */
    public Opened open(User user, Long addressId, PaymentMethod method, boolean useWallet, String couponCode) {
        PaymentGateway gateway = (method == null ? java.util.Optional.<PaymentGateway>empty() : gateways.forMethod(method))
                .orElseThrow(() -> new BusinessException((method == null ? "That way to pay" : method.getLabel())
                        + " isn't available right now. Choose another way to pay."));
        OrderService.Ready ready = orderService.ready(user, addressId, couponCode);
        OrderService.Due due = orderService.due(user, ready.cart().getTotal(), useWallet);
        if (due.rest().signum() <= 0) throw new BusinessException("MediZon Money covers the whole order: place it without paying online.");

        long paise = paise(due.rest());
        String gatewayOrderId = gateway.createOrder(paise, "MZ-" + user.getId() + "-" + System.currentTimeMillis());
        CheckoutPayment p = new CheckoutPayment();
        p.setUser(user);
        p.setGateway(gateway.id());
        p.setGatewayOrderId(gatewayOrderId);
        p.setMethod(method);
        p.setAddressId(addressId);
        p.setCouponCode(couponCode);
        p.setUseWallet(useWallet);
        p.setAmount(due.rest());
        payments.save(p);
        return new Opened(gateway.id(), gatewayOrderId, paise, method.getLabel(), RazorpayGateway.checkoutMethod(method),
                gateway.id() == Gateway.RAZORPAY ? razorpay.getKeyId() : null, MediZonUpiGateway.REQUEST_TIME.toSeconds());
    }

    /** MediZon UPI: checks a UPI ID before paying and returns the name it belongs to, as UPI apps show it. */
    public String verifyUpiId(User user, String upiId) {
        return upi.verify(upiId, user.getFullName());
    }

    /**
     * MediZon UPI: the customer approved the payment request in their UPI app (the PIN is checked in the browser and
     * never sent); the payment goes through and the orders are placed as from {@link #confirm}.
     */
    public Placed approveUpi(User user, String gatewayOrderId, String upiId) {
        CheckoutPayment p = payments.findByGatewayOrderIdAndUser(gatewayOrderId, user)
                .orElseThrow(() -> new NotFoundException("Payment not found."));
        if (p.getGateway() != Gateway.MEDIZON_UPI) throw new NotFoundException("Payment not found.");
        upi.approve(gatewayOrderId, upiId);
        return confirm(user, gatewayOrderId, null, null);
    }

    /**
     * The browser's checkout says the payment went through: checks it with the gateway, then places the orders as chosen
     * when it was opened. If they can't be placed the payment is refunded and the reason thrown. {@code paymentId} and
     * {@code signature} are Razorpay's.
     */
    public Placed confirm(User user, String gatewayOrderId, String paymentId, String signature) {
        CheckoutPayment p = payments.findByGatewayOrderIdAndUser(gatewayOrderId, user)
                .orElseThrow(() -> new NotFoundException("Payment not found."));
        if (p.getStatus() == CheckoutPayment.Status.PLACED) return placedFrom(p);   // sent twice: the orders are there
        if (p.getStatus() == CheckoutPayment.Status.REFUNDED) {
            throw new BusinessException("This payment couldn't become an order and was refunded (refund id " + p.getGatewayRefundId() + ").");
        }
        // Only one confirmation places the orders; a payment given up on may still be confirmed if it's paid after all
        if (payments.moveStatus(p.getId(), CheckoutPayment.Status.CREATED, CheckoutPayment.Status.PLACING) != 1
                && payments.moveStatus(p.getId(), CheckoutPayment.Status.EXPIRED, CheckoutPayment.Status.PLACING) != 1) {
            throw new BusinessException("This payment is already being confirmed. Your order will show under My orders in a moment.");
        }

        PaymentGateway gateway = gateways.get(p.getGateway());
        PaymentGateway.Paid pay;
        try {
            pay = gateway.confirm(gatewayOrderId, paymentId, signature);
            if (pay.amountPaise() != paise(p.getAmount())) throw new BusinessException("That payment doesn't match this order.");
        } catch (BusinessException e) {
            payments.moveStatus(p.getId(), CheckoutPayment.Status.PLACING, CheckoutPayment.Status.CREATED);   // can be tried again
            throw e;
        }
        p.setGatewayPaymentId(pay.id());
        PaymentMethod paidBy = pay.method() != null ? pay.method() : p.getMethod();

        // The cart, its prices or the MediZon Money balance may have changed while the customer was paying
        List<Order> placed;
        try {
            OrderService.Ready ready = orderService.ready(user, p.getAddressId(), p.getCouponCode());
            BigDecimal rest = orderService.due(user, ready.cart().getTotal(), p.isUseWallet()).rest();
            if (rest.compareTo(p.getAmount()) != 0) {
                throw new BusinessException("Your cart changed while you were paying: it's now ₹" + rest + " to pay, not ₹" + p.getAmount() + ".");
            }
            placed = orderService.checkout(user, paidBy, p.getAddressId(), p.getCouponCode(), p.isUseWallet(),
                    gateway.id(), gatewayOrderId, pay.id());
        } catch (BusinessException e) {
            // If the refund itself fails the payment stays PLACING, and settleLeftovers refunds it later
            String refundId = gateway.refund(gatewayOrderId, pay.id(), pay.amountPaise(), "Order couldn't be placed");
            finish(p, CheckoutPayment.Status.REFUNDED, refundId, null);
            notifications.notify(user, NotificationType.ORDER_UPDATE, "Payment refunded",
                    "₹" + p.getAmount() + " paid by " + paidBy.getLabel() + " couldn't become an order: " + e.getMessage()
                            + " It's refunded (refund id " + refundId + ").", "/cart");
            throw new BusinessException(e.getMessage() + " No order was placed, and your payment of ₹" + p.getAmount()
                    + " is refunded. Banks take up to 5-7 working days to show it.");
        }
        finish(p, CheckoutPayment.Status.PLACED, null, placed);
        notifications.notify(user, NotificationType.ORDER_UPDATE, "Payment received",
                "₹" + p.getAmount() + " received by " + paidBy.getLabel() + " through " + gateway.id().getLabel()
                        + " (payment id " + pay.id() + ").",
                redirect(placed.get(0).getId(), placed.size()));
        return placed(placed);
    }

    /** Pay on delivery or all MediZon Money, placed at once. */
    public Placed placeWithoutGateway(User user, Long addressId, PaymentMethod method, boolean useWallet, String couponCode) {
        if (method != PaymentMethod.COD && method != PaymentMethod.WALLET) {
            throw new BusinessException(method != null && method.isOnline()
                    ? "Pay by " + method.getLabel() + " first; the order is placed once it's paid." : "Choose how you'd like to pay.");
        }
        boolean allWallet = method == PaymentMethod.WALLET;
        return placed(orderService.checkout(user, allWallet ? null : method, addressId, couponCode, allWallet || useWallet));
    }

    /**
     * Every 5 minutes: payments left unconfirmed past {@code settle-minutes} are checked with their gateway. One that
     * became orders after all is marked so; one that took money without orders is refunded; one never paid is closed.
     */
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    public void settleLeftovers() {
        try {
            List<CheckoutPayment> open = payments.findOpenBefore(
                    List.of(CheckoutPayment.Status.CREATED, CheckoutPayment.Status.PLACING), LocalDateTime.now().minusMinutes(settleMinutes));
            for (CheckoutPayment p : open) {
                try {
                    settle(p);
                } catch (Exception e) {
                    log.warn("Couldn't settle payment {}: {}", p.getGatewayOrderId(), e.getMessage());   // tried again next run
                }
            }
        } catch (Exception e) {
            log.error("Payment settling failed", e);
        }
    }

    private void settle(CheckoutPayment p) {
        // A stuck PLACING one is taken over; a CREATED one only if the customer isn't confirming it right now
        if (p.getStatus() == CheckoutPayment.Status.CREATED
                && payments.moveStatus(p.getId(), CheckoutPayment.Status.CREATED, CheckoutPayment.Status.PLACING) != 1) return;
        List<Order> placed = orders.findByGatewayOrderIdOrderByIdAsc(p.getGatewayOrderId());
        if (!placed.isEmpty()) {
            finish(p, CheckoutPayment.Status.PLACED, null, placed);
            return;
        }
        PaymentGateway gateway = gateways.get(p.getGateway());
        String refundId = null;
        for (PaymentGateway.Paid pay : gateway.paidPayments(p.getGatewayOrderId())) {
            refundId = gateway.refund(p.getGatewayOrderId(), pay.id(), pay.amountPaise(), "Paid but no order was placed");
            p.setGatewayPaymentId(pay.id());
        }
        finish(p, refundId != null ? CheckoutPayment.Status.REFUNDED : CheckoutPayment.Status.EXPIRED, refundId, null);
        if (refundId != null) {
            log.info("Refunded payment {} of {} left without orders", p.getGatewayPaymentId(), p.getGatewayOrderId());
            notifications.notify(p.getUser(), NotificationType.ORDER_UPDATE, "Payment refunded",
                    "Your payment of ₹" + p.getAmount() + " wasn't confirmed, so no order was placed. It's refunded (refund id "
                            + refundId + "); banks take up to 5-7 working days to show it.", "/cart");
        }
    }

    private void finish(CheckoutPayment p, CheckoutPayment.Status status, String refundId, List<Order> placed) {
        p.setStatus(status);
        p.setUpdatedAt(LocalDateTime.now());
        if (refundId != null) p.setGatewayRefundId(refundId);
        if (placed != null) {
            p.setFirstOrderId(placed.get(0).getId());
            p.setOrderCount(placed.size());
        }
        payments.save(p);
    }

    private Placed placedFrom(CheckoutPayment p) {
        List<String> numbers = orders.findByGatewayOrderIdOrderByIdAsc(p.getGatewayOrderId()).stream().map(Order::getOrderNumber).toList();
        return new Placed(redirect(p.getFirstOrderId(), p.getOrderCount()), numbers,
                "₹" + p.getAmount() + " paid by " + p.getMethod().getLabel() + ".");
    }

    /** "₹120.00 paid by UPI.", "₹80.00 paid with MediZon Money. Pay ₹40.00 by cash or UPI when it arrives." */
    private static Placed placed(List<Order> placed) {
        BigDecimal wallet = placed.stream().map(Order::getWalletUsed).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rest = placed.stream().filter(o -> o.getPaymentMethod() != PaymentMethod.WALLET)
                .map(Order::getAmountDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        PaymentMethod method = placed.stream().map(Order::getPaymentMethod).filter(m -> m != PaymentMethod.WALLET)
                .findFirst().orElse(PaymentMethod.WALLET);
        String paid = (wallet.signum() > 0 ? "₹" + wallet + " paid with MediZon Money. " : "")
                + (rest.signum() <= 0 ? ""
                : method.isOnDelivery() ? "Pay ₹" + rest + " by cash or UPI when it arrives."
                : "₹" + rest + " paid by " + method.getLabel() + ".");
        return new Placed(redirect(placed.get(0).getId(), placed.size()),
                placed.stream().map(Order::getOrderNumber).collect(Collectors.toList()), paid.trim());
    }

    private static String redirect(Long firstOrderId, int count) {
        return count == 1 ? "/orders/" + firstOrderId : "/orders";
    }

    private static long paise(BigDecimal rupees) { return rupees.movePointRight(2).longValueExact(); }
}
