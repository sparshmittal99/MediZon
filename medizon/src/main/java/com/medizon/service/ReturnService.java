package com.medizon.service;

import com.medizon.domain.*;
import com.medizon.dto.AddressForm;
import com.medizon.dto.ReturnPickupForm;
import com.medizon.repository.ReturnRequestRepository;
import com.medizon.repository.UserRepository;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Returns: the customer picks packs from a delivered order and gives a reason and photos, then where to collect them
 * from and where the refund goes; the store that sold it (or the admin) approves or rejects; approved returns are
 * picked up (supply at home goes down, packs optionally go back on that store's shelf) and refunded: into MediZon Money
 * at once, or to UPI / a bank account / the card a few days after pickup (see {@link RefundMode#days}). Refunds are
 * what was paid for the packs, after any promo.
 */
@Service
@RequiredArgsConstructor
public class ReturnService {

    public static final int RETURN_WINDOW_DAYS = 7;
    public static final int MAX_IMAGES = 3;
    /** Pickups are booked this many days ahead. No courier slots yet, so the day is picked at random in the range. */
    public static final int PICKUP_MIN_DAYS = 1;
    public static final int PICKUP_MAX_DAYS = 3;

    private static final Pattern PIN_IN_TEXT = Pattern.compile("(?<![0-9])[1-9][0-9]{5}(?![0-9])");
    private static final Pattern UPI_ID = Pattern.compile("^[a-zA-Z0-9._-]{2,256}@[a-zA-Z]{2,64}$");
    private static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{9,18}$");
    private static final Pattern IFSC = Pattern.compile("^[A-Z]{4}0[A-Z0-9]{6}$");

    private final ReturnRequestRepository returns;
    private final OrderService orderService;
    private final MedicationPlanService planService;
    private final FileStorageService storage;
    private final NotificationService notifications;
    private final StaffAlerts staffAlerts;
    private final AddressService addressService;
    private final DeliveryAreaService deliveryAreas;
    private final Validator validator;
    private final WalletService wallet;

    // ------------------------------------------------------------------ customer

    /** Last day a return can be requested. */
    public LocalDate returnBy(Order o) {
        return o.getDeliveredAt() == null ? null : o.getDeliveredAt().toLocalDate().plusDays(RETURN_WINDOW_DAYS);
    }

    /** Does the order have anything that can be returned at all? Orders of only non-returnable items have no return option. */
    public boolean hasReturnableItems(Order o) {
        return o.getItems().stream().anyMatch(OrderItem::isReturnable);
    }

    /** Why the order can't be returned right now, or empty if it can. */
    public Optional<String> blocker(Order o) {
        if (!hasReturnableItems(o)) return Optional.of("The items in this order can't be returned.");
        if (o.getStatus() != OrderStatus.DELIVERED) return Optional.of("Returns open once the order is delivered.");
        if (LocalDate.now().isAfter(returnBy(o))) {
            return Optional.of("The return window closed on " + returnBy(o).format(DateTimeFormatter.ofPattern("d MMM yyyy")) + ".");
        }
        if (returns.findByOrderOrderByCreatedAtDesc(o).stream().anyMatch(ReturnRequest::isOpen)) {
            return Optional.of("A return for this order is already in progress.");
        }
        if (returnable(o).values().stream().allMatch(q -> q == 0)) return Optional.of("Everything returnable in this order has been returned.");
        return Optional.empty();
    }

    /** Packs of each returnable order line (by id) that can still be returned. Non-returnable lines are left out. */
    public Map<Long, Integer> returnable(Order o) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        remaining(o).forEach((id, left) -> {
            if (o.getItems().stream().anyMatch(i -> i.getId().equals(id) && i.isReturnable())) out.put(id, left);
        });
        return out;
    }

    /** Packs of every order line (by id) not yet returned, returnable or not. */
    private Map<Long, Integer> remaining(Order o) {
        Map<Long, Integer> out = new LinkedHashMap<>();
        for (OrderItem i : o.getItems()) {
            long done = returns.returnedQuantity(i, ReturnStatus.REJECTED);
            out.put(i.getId(), (int) Math.max(0, i.getQuantity() - done));
        }
        return out;
    }

    /** A pickup day for a return requested today. */
    public LocalDate pickupDate() {
        return LocalDate.now().plusDays(ThreadLocalRandom.current().nextInt(PICKUP_MIN_DAYS, PICKUP_MAX_DAYS + 1));
    }

    /** The date the form showed, while it's still in the pickup range; otherwise a fresh one. */
    LocalDate pickupDate(LocalDate shown) {
        LocalDate today = LocalDate.now();
        boolean ok = shown != null && !shown.isBefore(today.plusDays(PICKUP_MIN_DAYS)) && !shown.isAfter(today.plusDays(PICKUP_MAX_DAYS));
        return ok ? shown : pickupDate();
    }

    /** The PIN code of the address the order went to (the last one in the snapshot), or null. */
    public static String orderPincode(Order o) {
        if (o.getShippingAddress() == null) return null;
        Matcher m = PIN_IN_TEXT.matcher(o.getShippingAddress());
        String pin = null;
        while (m.find()) pin = m.group();
        return pin;
    }

    /** Can the packs be collected from where the order was delivered? */
    public boolean canPickUpFromOrderAddress(Order o) {
        return deliveryAreas.isServiceable(orderPincode(o));
    }

    @Transactional
    public ReturnRequest request(User user, Long orderId, List<Long> itemIds, List<Integer> quantities,
                                 ReturnReason reason, String details, List<MultipartFile> images, ReturnPickupForm pickup) {
        Order o = orderService.getForUser(orderId, user);
        blocker(o).ifPresent(b -> { throw new BusinessException(b); });
        if (reason == null) throw new BusinessException("Choose a reason for the return.");
        String d = details == null ? "" : details.trim();
        if (reason == ReturnReason.OTHER && d.isEmpty()) throw new BusinessException("Tell us what's wrong.");
        if (d.length() > 1000) throw new BusinessException("Keep the description under 1000 characters.");

        List<MultipartFile> photos = images == null ? List.of() : images.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (photos.isEmpty()) throw new BusinessException("Add at least one photo of the medicine and its packaging.");
        if (photos.size() > MAX_IMAGES) throw new BusinessException("Add at most " + MAX_IMAGES + " photos.");

        ReturnRequest r = new ReturnRequest();
        r.setReturnNumber(newReturnNumber());
        r.setOrder(o);
        r.setUser(user);
        r.setReason(reason);
        r.setDetails(d.isEmpty() ? null : d);

        Map<Long, Integer> left = returnable(o);
        Map<Long, OrderItem> byId = new HashMap<>();
        o.getItems().forEach(i -> byId.put(i.getId(), i));
        BigDecimal refund = BigDecimal.ZERO;
        for (int k = 0; itemIds != null && k < itemIds.size(); k++) {
            int qty = quantities != null && k < quantities.size() && quantities.get(k) != null ? quantities.get(k) : 0;
            if (qty <= 0) continue;
            OrderItem item = byId.get(itemIds.get(k));
            if (item == null) throw new BusinessException("That item isn't part of this order.");
            if (!item.isReturnable()) throw new BusinessException(item.getMedicineName() + " can't be returned.");
            if (qty > left.getOrDefault(item.getId(), 0)) {
                throw new BusinessException("You can return at most " + left.get(item.getId()) + " pack(s) of " + item.getMedicineName() + ".");
            }
            ReturnItem ri = new ReturnItem();
            ri.setOrderItem(item);
            ri.setQuantity(qty);
            ri.setAmount(paidFor(item, qty));
            r.addItem(ri);
            refund = refund.add(ri.getAmount());
        }
        if (r.getItems().isEmpty()) throw new BusinessException("Choose the packs you want to return.");
        r.setRefundAmount(refund);

        ReturnPickupForm p = pickup == null ? new ReturnPickupForm() : pickup;
        setRefundTo(r, o, p);
        setPickup(r, user, o, p);   // last check: it may save a new address

        photos.forEach(f -> r.getImages().add(new ReturnImage(storage.storeReturnImage(f), f.getContentType())));
        returns.save(r);

        notifications.notify(user, NotificationType.RETURN_UPDATE, "Return requested",
                "We got your return request " + r.getReturnNumber() + " for order " + o.getOrderNumber()
                        + ". A pharmacist will review it, usually within a day. Pickup is planned for "
                        + day(r.getPickupDate()) + ".", "/orders/" + o.getId());
        staffAlerts.notifyStore(o.getStore(), true, NotificationType.RETURN_UPDATE, "Return to review: " + r.getReturnNumber(),
                user.getFullName() + " wants to return " + r.getPackCount() + " pack(s) from order " + o.getOrderNumber()
                        + " (" + reason.getLabel().toLowerCase() + ").", "/admin/returns/" + r.getId());
        return r;
    }

    private void setPickup(ReturnRequest r, User user, Order o, ReturnPickupForm f) {
        String from = f.getPickupFrom() == null || f.getPickupFrom().isBlank() ? ReturnPickupForm.ORDER_ADDRESS : f.getPickupFrom();
        String name, phone, address, pin;
        if (from.equals(ReturnPickupForm.ORDER_ADDRESS)) {
            name = o.getDeliverTo();
            phone = o.getContactPhone();
            address = o.getShippingAddress();
            pin = orderPincode(o);
        } else if (from.equals(ReturnPickupForm.NEW_ADDRESS)) {
            AddressForm a = f.getNewAddress() == null ? new AddressForm() : f.getNewAddress();
            a.setId(null);
            a.setMakeDefault(false);
            validator.validate(a).stream().map(ConstraintViolation::getMessage).sorted().findFirst()
                    .ifPresent(m -> { throw new BusinessException("Pickup address: " + m + "."); });
            pin = DeliveryAreaService.normalize(a.getPincode());
            requirePickupArea(pin);
            Address typed;
            if (f.isSaveAddress()) {
                typed = addressService.save(user, a);
            } else {
                typed = new Address();
                typed.setAddressLine(a.getAddressLine().trim());
                typed.setArea(a.getArea());
                typed.setLandmark(a.getLandmark());
                typed.setCity(a.getCity().trim());
                typed.setState(a.getState());
                typed.setPincode(pin);
            }
            name = a.getReceiverName().trim();
            phone = a.getReceiverPhone().trim();
            address = typed.getFormatted();
        } else {
            Address a;
            try {
                a = addressService.get(Long.valueOf(from), user);
            } catch (NumberFormatException e) {
                throw new BusinessException("Choose where we should pick the packs up from.");
            }
            name = a.getReceiverName();
            phone = a.getReceiverPhone();
            address = a.getFormatted();
            pin = a.getPincode();
        }
        requirePickupArea(pin);
        r.setPickupName(name);
        r.setPickupPhone(phone);
        r.setPickupAddress(address);
        r.setPickupDate(pickupDate(f.getPickupDate()));
    }

    private void requirePickupArea(String pincode) {
        if (pincode == null) throw new BusinessException("The pickup address needs a valid 6 digit PIN code.");
        if (!deliveryAreas.isServiceable(pincode)) {
            throw new BusinessException("Sorry, we can't pick up from PIN code " + pincode + " yet. Choose another address.");
        }
    }

    /** What the customer paid for {@code qty} packs of the line: its share of the promo is not refunded. */
    static BigDecimal paidFor(OrderItem item, int qty) {
        if (qty >= item.getQuantity()) return item.getNetTotal();
        return item.getNetTotal().multiply(BigDecimal.valueOf(qty)).divide(BigDecimal.valueOf(item.getQuantity()), 2, java.math.RoundingMode.DOWN);
    }

    /**
     * Orders paid all in MediZon Money go back to it; prepaid orders to the method used or MediZon Money; cash orders
     * to MediZon Money, a UPI ID or a bank account.
     */
    private void setRefundTo(ReturnRequest r, Order o, ReturnPickupForm f) {
        if (o.getPaymentMethod() == PaymentMethod.WALLET || f.getRefundMode() == RefundMode.WALLET) {
            r.setRefundMode(RefundMode.WALLET);
            return;
        }
        if (o.getPaymentMethod() != PaymentMethod.COD) {
            r.setRefundMode(RefundMode.ORIGINAL);
            return;
        }
        if (f.getRefundMode() == RefundMode.UPI) {
            String upi = f.getUpiId() == null ? "" : f.getUpiId().trim();
            if (!UPI_ID.matcher(upi).matches()) throw new BusinessException("Enter a valid UPI ID, like name@okbank.");
            r.setRefundMode(RefundMode.UPI);
            r.setRefundUpiId(upi);
        } else if (f.getRefundMode() == RefundMode.BANK) {
            String holder = f.getAccountName() == null ? "" : f.getAccountName().trim();
            String number = digits(f.getAccountNumber());
            String ifsc = f.getIfsc() == null ? "" : f.getIfsc().trim().toUpperCase(Locale.ROOT);
            if (holder.isEmpty() || holder.length() > 80) throw new BusinessException("Enter the account holder's name as the bank has it.");
            if (!ACCOUNT_NUMBER.matcher(number).matches()) throw new BusinessException("Enter a bank account number of 9 to 18 digits.");
            if (!number.equals(digits(f.getConfirmAccountNumber()))) throw new BusinessException("The account numbers don't match.");
            if (!IFSC.matcher(ifsc).matches()) throw new BusinessException("Enter a valid 11 character IFSC, like HDFC0001234.");
            r.setRefundMode(RefundMode.BANK);
            r.setRefundAccountName(holder);
            r.setRefundAccountNumber(number);
            r.setRefundIfsc(ifsc);
        } else {
            throw new BusinessException("This order was paid in cash. Choose MediZon Money, UPI or a bank account for the refund.");
        }
    }

    private static String digits(String s) { return s == null ? "" : s.replaceAll("\\s+", ""); }

    public List<ReturnRequest> forOrder(Order o) { return returns.findByOrderOrderByCreatedAtDesc(o); }

    public List<ReturnRequest> forUser(User user) { return returns.findByUserOrderByCreatedAtDesc(user); }

    /** The customer, the admin and the store that sold the order can see a return's photos; anyone else gets "not found". */
    @Transactional(readOnly = true)
    public ReturnImage image(Long returnId, int index, User viewer) {
        ReturnRequest r = viewer.isAdmin() ? get(returnId)
                : viewer.isStoreStaff() ? returns.findById(returnId)
                        .filter(x -> viewer.getStore() != null && x.getOrder().getStore() != null
                                && viewer.getStore().getId().equals(x.getOrder().getStore().getId()))
                        .orElseThrow(() -> new NotFoundException("File not found."))
                : returns.findByIdAndUser(returnId, viewer).orElseThrow(() -> new NotFoundException("File not found."));
        if (index < 0 || index >= r.getImages().size()) throw new NotFoundException("File not found.");
        return r.getImages().get(index);
    }

    public Resource file(ReturnImage image) { return storage.load(image.getStoredFileName()); }

    // ------------------------------------------------------------------ admin

    public ReturnRequest get(Long id) {
        return returns.findById(id).orElseThrow(() -> new NotFoundException("Return not found."));
    }

    /** Returns for the back office: one store's, or every store's when {@code store} is null. */
    public List<ReturnRequest> adminList(ReturnStatus status, Store store) {
        if (store != null) {
            return status == null ? returns.findByOrderStoreOrderByCreatedAtDesc(store) : returns.findByOrderStoreAndStatusOrderByCreatedAtAsc(store, status);
        }
        return status == null ? returns.findAllByOrderByCreatedAtDesc() : returns.findByStatusOrderByCreatedAtAsc(status);
    }

    public Map<ReturnStatus, Long> adminCounts(Store store) {
        Map<ReturnStatus, Long> counts = new EnumMap<>(ReturnStatus.class);
        for (ReturnStatus s : ReturnStatus.values()) {
            counts.put(s, store != null ? returns.countByOrderStoreAndStatus(store, s) : returns.countByStatus(s));
        }
        return counts;
    }

    public long countAwaitingReview(Store store) {
        return store == null ? returns.countByStatus(ReturnStatus.REQUESTED) : returns.countByOrderStoreAndStatus(store, ReturnStatus.REQUESTED);
    }

    public long countAwaitingReview() { return returns.countByStatus(ReturnStatus.REQUESTED); }

    public BigDecimal totalRefunded() { return returns.totalRefunded(ReturnStatus.REFUNDED); }

    @Transactional
    public ReturnRequest decide(Long id, boolean approve, String note) {
        ReturnRequest r = move(id, approve ? ReturnStatus.APPROVED : ReturnStatus.REJECTED);
        String n = note == null || note.isBlank() ? null : note.trim();
        if (!approve && n == null) throw new BusinessException("Say why the return is rejected; the customer sees this.");
        r.setAdminNote(n);
        r.setDecidedAt(LocalDateTime.now());
        // Approved on or after the planned day: book the next one
        if (approve && r.getPickupDate() != null && r.getPickupDate().isBefore(LocalDate.now().plusDays(PICKUP_MIN_DAYS))) {
            r.setPickupDate(pickupDate());
        }
        notifyCustomer(r, approve ? "Return approved" : "Return not approved", approve
                ? "Return " + r.getReturnNumber() + " is approved. Keep the packs ready; our delivery partner will pick them up"
                + (r.getPickupDate() == null ? "." : " on " + day(r.getPickupDate()) + ".")
                + (n == null ? "" : " Note: " + n)
                : "Return " + r.getReturnNumber() + " couldn't be approved: " + n);
        return r;
    }

    @Transactional
    public ReturnRequest markPickedUp(Long id, boolean restock) {
        ReturnRequest r = move(id, ReturnStatus.PICKED_UP);
        r.setPickedUpAt(LocalDateTime.now());
        for (ReturnItem ri : r.getItems()) {
            planService.onItemsReturned(ri.getOrderItem(), ri.getQuantity());
            if (restock) orderService.restock(r.getOrder(), ri.getOrderItem().getMedicine(), ri.getQuantity());
        }
        r.setRestocked(restock);

        // MediZon Money is paid at once: all of it when chosen, else whatever of the order it paid for
        Order o = r.getOrder();
        BigDecimal walletLeft = o.getWalletUsed().subtract(o.getWalletRefunded()).max(BigDecimal.ZERO);
        BigDecimal toWallet = r.getRefundMode() == RefundMode.WALLET ? r.getRefundAmount() : r.getRefundAmount().min(walletLeft);
        if (toWallet.signum() > 0) {
            wallet.credit(r.getUser(), toWallet, "Refund for return " + r.getReturnNumber(), "/orders/" + o.getId());
            o.setWalletRefunded(o.getWalletRefunded().add(toWallet.min(walletLeft)));
            r.setWalletRefund(toWallet);
        }
        if (r.getPayoutAmount().signum() <= 0) {
            notifyCustomer(r, "Refund added to MediZon Money", "We've collected the packs for return " + r.getReturnNumber()
                    + ". ₹" + toWallet + " is in your MediZon Money now, ready for your next order.");
            return finishRefund(r, false);
        }
        int days = r.getRefundMode() == null ? 7 : r.getRefundMode().days(o.getPaymentMethod());
        r.setRefundDueOn(LocalDate.now().plusDays(days));
        notifyCustomer(r, "Return picked up", "We've collected the packs for return " + r.getReturnNumber() + "."
                + (toWallet.signum() > 0 ? " ₹" + toWallet + " is back in your MediZon Money." : "")
                + " ₹" + r.getPayoutAmount() + " will reach " + (r.getRefundTo() == null ? "you" : r.getRefundTo())
                + " by " + day(r.getRefundDueOn()) + ".");
        return r;
    }

    /** Marks a picked-up return refunded: by staff, or by {@link #refundDue()} when its day comes. */
    @Transactional
    public ReturnRequest refund(Long id) {
        return finishRefund(get(id), true);
    }

    /** Picked-up returns whose refund day has come are paid out. Payments are simulated, as at checkout. */
    @Transactional
    public int refundDue() {
        List<ReturnRequest> due = returns.findByStatusAndRefundDueOnLessThanEqual(ReturnStatus.PICKED_UP, LocalDate.now());
        due.forEach(r -> finishRefund(r, true));
        return due.size();
    }

    private ReturnRequest finishRefund(ReturnRequest r, boolean tell) {
        if (!r.getStatus().canMoveTo(ReturnStatus.REFUNDED)) {
            throw new BusinessException("A return that is " + r.getStatus().getLabel().toLowerCase() + " can't be marked refunded.");
        }
        r.setStatus(ReturnStatus.REFUNDED);
        r.setRefundedAt(LocalDateTime.now());
        Order o = r.getOrder();
        if (remaining(o).values().stream().allMatch(q -> q == 0)) o.setPaymentStatus(PaymentStatus.REFUNDED);
        if (tell && r.getPayoutAmount().signum() > 0) {
            notifyCustomer(r, "Refund processed", "₹" + r.getPayoutAmount() + " for return " + r.getReturnNumber()
                    + " is refunded" + (r.getRefundTo() == null ? "." : " to " + r.getRefundTo() + "."));
        }
        return r;
    }

    private ReturnRequest move(Long id, ReturnStatus next) {
        ReturnRequest r = get(id);
        if (!r.getStatus().canMoveTo(next)) {
            throw new BusinessException("A return that is " + r.getStatus().getLabel().toLowerCase()
                    + " can't be marked " + next.getLabel().toLowerCase() + ".");
        }
        r.setStatus(next);
        return r;
    }

    private void notifyCustomer(ReturnRequest r, String title, String message) {
        notifications.notify(r.getUser(), NotificationType.RETURN_UPDATE, title, message, "/orders/" + r.getOrder().getId());
    }

    private static String day(LocalDate d) {
        return d.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.ENGLISH));
    }

    private String newReturnNumber() {
        return "RT" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyMMdd"))
                + String.format("%05d", new Random().nextInt(100_000));
    }
}
