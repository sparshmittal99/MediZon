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
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Paying for the cart on the payment page. Online methods go through the gateway that takes them (Razorpay for cards
 * and net banking, MediZon UPI for UPI; see {@link PaymentGateways}) and the orders are placed only once the payment is
 * confirmed with it. Pay on delivery and MediZon Money place them straight away.
 *
 * <p>A payment that went through always becomes its orders, even when the browser never comes back to say so (the app
 * was closed, or the connection dropped, right after the OTP). The server learns of it on its own, whichever comes
 * first, and each goes through {@link #reconcile} so the orders are placed exactly once:
 * <ul>
 *   <li>the browser's confirmation ({@link #confirm});</li>
 *   <li>the gateway's webhook ({@link #onGatewayPaid}), within seconds of the payment;</li>
 *   <li>the customer coming back to the cart, checkout or payment page ({@link #recover});</li>
 *   <li>a check every minute of payments not yet confirmed ({@link #reconcileOpen}).</li>
 * </ul>
 * Money is never kept without an order: a payment whose orders can't be placed (the cart changed, stock ran out) is
 * refunded at once.
 *
 * <p>While the customer pays, the cart's packs are held for them ({@link StockHoldService}), so nobody else can buy the
 * last ones meanwhile; one paid after its hold ran out is placed if the stock is still there, else refunded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    /** How long the browser has to confirm a payment itself before the server checks it with the gateway. */
    private static final Duration BROWSER_GRACE = Duration.ofMinutes(1);
    /** A placing that hasn't finished in this long stopped half way (the app restarted) and is taken over. */
    private static final Duration STUCK = Duration.ofMinutes(5);
    /** How often the cart and checkout pages may ask the gateway about the same open payment. */
    private static final Duration RECHECK = Duration.ofSeconds(30);

    private final PaymentGateways gateways;
    private final RazorpayGateway razorpay;
    private final MediZonUpiGateway upi;
    private final CheckoutPaymentRepository payments;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final NotificationService notifications;
    private final OrderSettingsService orderSettings;
    private final StockHoldService stockHolds;

    /** Minutes a payment is checked for before, still unpaid, it's closed. A later payment still arrives by webhook. */
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

    /**
     * Where a payment stands, for the payment page when it couldn't hear back directly: {@code PLACED} (with the orders),
     * {@code PENDING} (being placed right now), {@code REFUNDED} (with why) or {@code UNPAID} (nothing paid yet).
     */
    public record Check(String state, Placed placed, String message) {}

    /** Starts paying the cart online: a gateway order for what MediZon Money doesn't cover. Nothing is placed yet. */
    public Opened open(User user, Long addressId, PaymentMethod method, boolean useWallet, String couponCode) {
        // An earlier payment that went through (its app closed before it could say so) is placed first, not paid twice
        List<Placed> earlier = recover(user);
        if (!earlier.isEmpty()) throw new BusinessException(recoveredMessage(earlier) + " Check My orders before paying again.");
        PaymentGateway gateway = (method == null ? java.util.Optional.<PaymentGateway>empty() : gateways.forMethod(method))
                .orElseThrow(() -> new BusinessException((method == null ? "That way to pay" : method.getLabel())
                        + " isn't available right now. Choose another way to pay."));
        OrderService.Ready ready = orderService.ready(user, addressId, couponCode);
        OrderService.Due due = orderService.due(user, ready.cart().getTotal(), useWallet);
        if (due.rest().signum() <= 0) throw new BusinessException("MediZon Money covers the whole order: place it without paying online.");

        // The charge for paying this way (card, net banking ...) is set here, by the server, and kept with the payment
        OrderSettingsService.PaymentCharge charge = orderSettings.paymentCharge(method);
        BigDecimal amount = due.rest().add(charge.amount());
        long paise = paise(amount);
        String gatewayOrderId = gateway.createOrder(paise, "MZ-" + user.getId() + "-" + System.currentTimeMillis());
        CheckoutPayment p = new CheckoutPayment();
        p.setUser(user);
        p.setGateway(gateway.id());
        p.setGatewayOrderId(gatewayOrderId);
        p.setMethod(method);
        p.setAddressId(addressId);
        p.setCouponCode(couponCode);
        p.setUseWallet(useWallet);
        p.setAmount(amount);
        p.setPaymentFee(charge.amount());
        p.setPaymentFeeGstRate(charge.gstRate());
        // Saved with the cart's packs held for it while they pay; if someone else got the last ones first, it stops
        // here, before anything is paid (the gateway order is left unpaid, and lapses)
        stockHolds.open(p, ready.cart());
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
        if (p.getStatus() == CheckoutPayment.Status.PLACED) return placedFrom(p);   // sent twice, or placed by the webhook
        if (p.getStatus() == CheckoutPayment.Status.REFUNDED) throw new BusinessException(refundedMessage(p));
        CheckoutPayment.Status from = p.getStatus();
        // Only one confirmation places the orders; a payment given up on may still be confirmed if it's paid after all
        if (!claim(p)) {
            throw new BusinessException("This payment is already being confirmed. Your order will show under My orders in a moment.");
        }
        PaymentGateway gateway = gateways.get(p.getGateway());
        PaymentGateway.Paid pay;
        try {
            pay = gateway.confirm(gatewayOrderId, paymentId, signature);
        } catch (RuntimeException e) {
            release(p, from);   // checked again with the gateway by the reconciler, or tried again
            throw e;
        }
        return place(p, user, gateway, pay);
    }

    /**
     * Where the payment for {@code gatewayOrderId} stands, checked with the gateway first if it isn't confirmed yet: the
     * payment page asks when the browser couldn't confirm it itself (connection dropped, the gateway's window closed).
     */
    public Check check(User user, String gatewayOrderId) {
        CheckoutPayment p = payments.findWithUserByGatewayOrderId(gatewayOrderId)
                .filter(x -> x.getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new NotFoundException("Payment not found."));
        if (p.getStatus() == CheckoutPayment.Status.CREATED || p.getStatus() == CheckoutPayment.Status.EXPIRED) {
            try {
                reconcile(p, user, false);
            } catch (RuntimeException e) {
                log.warn("Couldn't check payment {}: {}", gatewayOrderId, e.getMessage());
            }
            p = payments.findById(p.getId()).orElseThrow();
        }
        return switch (p.getStatus()) {
            case PLACED -> new Check("PLACED", placedFrom(p), null);
            case REFUNDED -> new Check("REFUNDED", null, refundedMessage(p));
            case PLACING -> new Check("PENDING", null, null);
            default -> new Check("UNPAID", null, null);
        };
    }

    /**
     * The gateway says {@code gatewayOrderId} was paid (its webhook): the orders are placed now, whether or not the
     * customer's browser is still there. Nothing happens for a payment already placed, refunded or being placed.
     *
     * @throws RuntimeException when the gateway can't be reached, so the webhook is sent again
     */
    public void onGatewayPaid(String gatewayOrderId) {
        CheckoutPayment p = payments.findWithUserByGatewayOrderId(gatewayOrderId).orElse(null);
        if (p == null) {
            log.info("Webhook for gateway order {}, which isn't a checkout payment", gatewayOrderId);
            return;
        }
        if (p.getStatus() == CheckoutPayment.Status.CREATED || p.getStatus() == CheckoutPayment.Status.EXPIRED) {
            reconcile(p, p.getUser(), false);
        }
    }

    /**
     * The customer is back (the cart, checkout, the payment page, or paying again): each payment of theirs not yet
     * confirmed is checked with its gateway now, so one paid just before the app closed becomes its orders before they
     * shop on, rather than them paying a second time. Returns what got placed. Never throws.
     */
    public List<Placed> recover(User user) {
        List<Placed> out = new ArrayList<>();
        try {
            // Each payment is asked about at most every RECHECK, so pages stay fast while one is left open (a payment
            // cancelled in the gateway's window stays open until it's closed); each check moves updatedAt on
            LocalDateTime recheckBefore = LocalDateTime.now().minus(RECHECK);
            for (CheckoutPayment p : payments.findByUserAndStatusInOrderByIdAsc(user, List.of(CheckoutPayment.Status.CREATED))) {
                if (p.getUpdatedAt() != null && p.getUpdatedAt().isAfter(recheckBefore)) continue;
                try {
                    reconcile(p, user, false);
                    payments.findById(p.getId()).filter(x -> x.getStatus() == CheckoutPayment.Status.PLACED)
                            .ifPresent(x -> out.add(placedFrom(x)));
                } catch (RuntimeException e) {
                    log.warn("Couldn't check payment {}: {}", p.getGatewayOrderId(), e.getMessage());
                }
            }
        } catch (RuntimeException e) {
            log.warn("Couldn't check open payments of user {}: {}", user.getId(), e.getMessage());
        }
        return out;
    }

    /** "Your earlier payment went through: ₹120.00 paid by Card. Order MZ123 is placed." */
    public static String recoveredMessage(List<Placed> placed) {
        List<String> numbers = placed.stream().flatMap(p -> p.orderNumbers().stream()).toList();
        return "Your earlier payment went through: " + placed.stream().map(Placed::paid).collect(Collectors.joining(" "))
                + (numbers.size() == 1 ? " Order " + numbers.get(0) + " is placed." : " Orders " + String.join(", ", numbers) + " are placed.");
    }

    /** Pay on delivery or all MediZon Money, placed at once. */
    public Placed placeWithoutGateway(User user, Long addressId, PaymentMethod method, boolean useWallet, String couponCode) {
        if (method != PaymentMethod.COD && method != PaymentMethod.WALLET) {
            throw new BusinessException(method != null && method.isOnline()
                    ? "Pay by " + method.getLabel() + " first; the order is placed once it's paid." : "Choose how you'd like to pay.");
        }
        boolean allWallet = method == PaymentMethod.WALLET;
        OrderSettingsService.PaymentCharge charge = OrderSettingsService.PaymentCharge.NONE;
        if (!allWallet) {
            // Pay on delivery's charge, when something is left to pay at the door
            BigDecimal total = orderService.ready(user, addressId, couponCode).cart().getTotal();
            if (orderService.due(user, total, useWallet).rest().signum() > 0) charge = orderSettings.paymentCharge(method);
        }
        return placed(orderService.checkout(user, allWallet ? null : method, addressId, couponCode, allWallet || useWallet, charge));
    }

    /**
     * Every minute: payments the browser hasn't confirmed within {@link #BROWSER_GRACE} (it closed, or lost its
     * connection) and placings stuck half way are checked with their gateway. One paid becomes its orders (or is refunded
     * if they can't be placed); one still unpaid after {@code settle-minutes} is closed.
     */
    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    public void reconcileOpen() {
        try {
            LocalDateTime now = LocalDateTime.now();
            List<CheckoutPayment> open = payments.findToReconcile(CheckoutPayment.Status.CREATED, now.minus(BROWSER_GRACE),
                    CheckoutPayment.Status.PLACING, now.minus(STUCK));
            for (CheckoutPayment p : open) {
                try {
                    reconcile(p, p.getUser(), p.getCreatedAt().isBefore(now.minusMinutes(settleMinutes)));
                } catch (Exception e) {
                    log.warn("Couldn't check payment {}: {}", p.getGatewayOrderId(), e.getMessage());   // tried again next run
                }
            }
        } catch (Exception e) {
            log.error("Payment reconciling failed", e);
        }
    }

    /**
     * Checks a payment the browser hasn't confirmed with its gateway, without the browser: paid, its orders are placed
     * (or it's refunded if they can't be); unpaid, it's left open, or closed when {@code mayExpire}. A payment someone
     * else is confirming right now is left to them; a stuck placing ({@link #reconcileOpen} only) is taken over.
     */
    private void reconcile(CheckoutPayment p, User user, boolean mayExpire) {
        CheckoutPayment.Status from = p.getStatus() == CheckoutPayment.Status.PLACING ? CheckoutPayment.Status.CREATED : p.getStatus();
        if (p.getStatus() == CheckoutPayment.Status.PLACING) {
            // Only a placing that is still stuck right now, and only by one caller; one in progress is left alone
            LocalDateTime now = LocalDateTime.now();
            if (payments.takeOverStuck(p.getId(), CheckoutPayment.Status.PLACING, now.minus(STUCK), now) != 1) return;
        } else if (!claim(p)) {
            return;
        }
        // Placed before the app stopped, but not marked so
        List<Order> placed = orders.findByGatewayOrderIdOrderByIdAsc(p.getGatewayOrderId());
        if (!placed.isEmpty()) {
            finish(p, CheckoutPayment.Status.PLACED, null, placed);
            return;
        }
        PaymentGateway gateway = gateways.get(p.getGateway());
        Optional<PaymentGateway.Paid> pay;
        try {
            pay = gateway.findPaid(p.getGatewayOrderId());
        } catch (RuntimeException e) {
            release(p, from);
            throw e;
        }
        if (pay.isEmpty()) {
            if (mayExpire) finish(p, CheckoutPayment.Status.EXPIRED, null, null);
            else release(p, from);
            return;
        }
        try {
            place(p, user, gateway, pay.get());
            log.info("Placed the orders of payment {} ({}) without the browser", pay.get().id(), p.getGatewayOrderId());
        } catch (BusinessException e) {
            // Refunded and the customer told; or the refund failed, and the placing is taken over once it's stuck
            log.info("Payment {} ({}) couldn't become orders: {}", pay.get().id(), p.getGatewayOrderId(), e.getMessage());
        }
    }

    /**
     * Places the orders paid by {@code pay}, as chosen when the payment was opened, and marks the payment PLACED. If they
     * can't be placed the payment is refunded, the customer told, and the reason thrown. Called while holding PLACING.
     */
    private Placed place(CheckoutPayment p, User user, PaymentGateway gateway, PaymentGateway.Paid pay) {
        p.setGatewayPaymentId(pay.id());
        PaymentMethod paidBy = pay.method() != null ? pay.method() : p.getMethod();
        String gatewayOrderId = p.getGatewayOrderId();

        // Already placed for this payment (by another path): never place twice, never refund
        List<Order> existing = orders.findByGatewayOrderIdOrderByIdAsc(gatewayOrderId);
        if (!existing.isEmpty()) {
            finish(p, CheckoutPayment.Status.PLACED, null, existing);
            return placed(existing);
        }

        // The cart, its prices or the MediZon Money balance may have changed while the customer was paying
        List<Order> placed;
        try {
            if (pay.amountPaise() != paise(p.getAmount())) throw new BusinessException("That payment doesn't match this order.");
            OrderService.Ready ready = orderService.ready(user, p.getAddressId(), p.getCouponCode());
            BigDecimal fee = p.getPaymentFee() == null ? BigDecimal.ZERO : p.getPaymentFee();
            BigDecimal rest = orderService.due(user, ready.cart().getTotal(), p.isUseWallet()).rest().add(fee);
            if (rest.compareTo(p.getAmount()) != 0) {
                throw new BusinessException("Your cart changed while you were paying: it's now ₹" + rest + " to pay, not ₹" + p.getAmount() + ".");
            }
            OrderSettingsService.PaymentCharge charge = fee.signum() > 0
                    ? new OrderSettingsService.PaymentCharge(fee, p.getPaymentFeeGstRate() == null ? BigDecimal.ZERO : p.getPaymentFeeGstRate())
                    : OrderSettingsService.PaymentCharge.NONE;
            placed = orderService.checkout(user, paidBy, p.getAddressId(), p.getCouponCode(), p.isUseWallet(), charge,
                    gateway.id(), gatewayOrderId, pay.id());
        } catch (BusinessException e) {
            // A payment whose orders exist is never refunded: they were placed by another path meanwhile
            List<Order> meanwhile = orders.findByGatewayOrderIdOrderByIdAsc(gatewayOrderId);
            if (!meanwhile.isEmpty()) {
                log.warn("Payment {} ({}) was placed meanwhile by another path; not refunded", pay.id(), gatewayOrderId);
                finish(p, CheckoutPayment.Status.PLACED, null, meanwhile);
                return placed(meanwhile);
            }
            // If the refund itself fails the payment stays PLACING, and reconcileOpen takes it over once it's stuck
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

    /** Takes a payment to place it (from CREATED, or EXPIRED if it was paid after all); true when this caller won. */
    private boolean claim(CheckoutPayment p) {
        return payments.moveStatus(p.getId(), CheckoutPayment.Status.CREATED, CheckoutPayment.Status.PLACING) == 1
                || payments.moveStatus(p.getId(), CheckoutPayment.Status.EXPIRED, CheckoutPayment.Status.PLACING) == 1;
    }

    /** Gives a claimed payment back, to be checked again. */
    private void release(CheckoutPayment p, CheckoutPayment.Status to) {
        payments.moveStatus(p.getId(), CheckoutPayment.Status.PLACING, to);
    }

    private static String refundedMessage(CheckoutPayment p) {
        return "This payment couldn't become an order and was refunded (refund id " + p.getGatewayRefundId()
                + "). Banks take up to 5-7 working days to show it.";
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
        stockHolds.release(p.getId());   // placed, refunded or closed: nothing to hold any more
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
        return count == 1 ? "/orders/" + com.medizon.domain.OrderRef.encode(firstOrderId) : "/orders";
    }

    private static long paise(BigDecimal rupees) { return rupees.movePointRight(2).longValueExact(); }
}
