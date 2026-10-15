package com.medizon.service;

import com.medizon.domain.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Deleting an account. Everything personal goes: addresses, family members, medicines and reminders, prescriptions
 * (with their files), notifications, devices, the cart, reviews and feedback. What the accounts must keep stays,
 * stripped of personal details: orders and their tax invoices (amounts, GST, place of supply, invoice numbers; a
 * business buyer's GSTIN and billing name, which a B2B invoice must show) and the refunds on returns. The account row
 * is renamed "Deleted customer" so those records still point at something, and can never sign in again.
 *
 * Customers delete their own account; the admin can delete any account except its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountDeletionService {

    private final JdbcTemplate jdbc;
    private final UserService userService;
    private final FileStorageService storage;
    private final PasswordEncoder encoder;

    /** A customer deleting their own account, confirmed with their password. */
    @Transactional
    public void deleteOwn(User user, String password) {
        if (!user.isCustomer()) throw new BusinessException("Staff accounts are deleted by the admin.");
        if (!userService.checkPassword(user, password)) throw new BusinessException("That password isn't right.");
        delete(user);
    }

    /** The admin deleting another account; returns the name it had. */
    @Transactional
    public String deleteByAdmin(Long id, User admin) {
        if (!admin.isAdmin()) throw new BusinessException("Only the admin can delete other accounts.");
        User u = userService.get(id);
        if (u.getId().equals(admin.getId())) throw new BusinessException("The admin account can't be deleted.");
        if (u.isDeleted()) throw new BusinessException("That account is already deleted.");
        String name = u.getFullName();
        delete(u);
        return name;
    }

    private void delete(User u) {
        long id = u.getId();
        userService.signOutEverywhere(u);

        // Uploaded files first, while we still know which they are
        List<String> files = jdbc.queryForList("select stored_file_name from prescription where user_id = ?", String.class, id);
        files.addAll(jdbc.queryForList("select i.stored_file_name from return_images i join return_requests r on r.id = i.return_id "
                + "where r.user_id = ?", String.class, id));

        // Who each ordered medicine was for (family members) is personal; amounts and tax stay
        jdbc.update("delete from order_item_members where order_item_id in (select i.id from order_item i join orders o on o.id = i.order_id where o.user_id = ?)", id);
        jdbc.update("delete from order_item_packs where order_item_id in (select i.id from order_item i join orders o on o.id = i.order_id where o.user_id = ?)", id);
        jdbc.update("""
                update orders set shipping_address = null, contact_phone = null, receiver_name = null, address_label = null,
                       prescription_id = null,
                       billing_name = case when billing_gstin is null or billing_gstin = '' then 'Deleted customer' else billing_name end,
                       billing_address = case when billing_gstin is null or billing_gstin = '' then null else billing_address end
                where user_id = ?""", id);
        // Refunds stay for the accounts; the reasons in the customer's words and the photos go
        jdbc.update("delete from return_images where return_id in (select id from return_requests where user_id = ?)", id);
        jdbc.update("update return_requests set details = null where user_id = ?", id);

        jdbc.update("delete from reviews where user_id = ?", id);
        jdbc.update("delete from feedback where user_id = ?", id);
        jdbc.update("delete from prescription where user_id = ?", id);
        jdbc.update("delete from notification where user_id = ?", id);
        jdbc.update("delete from notification_device_members where device_id in (select id from notification_devices where user_id = ?)", id);
        jdbc.update("delete from notification_devices where user_id = ?", id);
        jdbc.update("delete from reminder where user_id = ?", id);
        jdbc.update("delete from dose_log where plan_id in (select id from medication_plan where user_id = ?)", id);
        for (String t : new String[]{"plan_reminder_times", "plan_days", "plan_dates"}) {
            jdbc.update("delete from " + t + " where plan_id in (select id from medication_plan where user_id = ?)", id);
        }
        jdbc.update("delete from medication_plan where user_id = ?", id);
        jdbc.update("delete from cart_item_members where cart_item_id in (select id from cart_item where user_id = ?)", id);
        jdbc.update("delete from cart_item_packs where cart_item_id in (select id from cart_item where user_id = ?)", id);
        jdbc.update("delete from cart_item where user_id = ?", id);
        jdbc.update("delete from addresses where user_id = ?", id);
        jdbc.update("delete from family_members where user_id = ?", id);

        String role = u.getRole().getLabel().toLowerCase();
        jdbc.update("""
                update users set full_name = ?, email = ?, phone = null, password = ?, address_line = null, city = null,
                       state = null, pincode = null, billing_name = null, billing_address_line = null, billing_city = null,
                       billing_state = null, billing_pincode = null, billing_gstin = null, store_id = null,
                       enabled = false, deleted = true, deleted_at = ?
                where id = ?""",
                "Deleted " + role, "deleted-" + id + "@deleted.invalid", encoder.encode(UUID.randomUUID().toString()),
                LocalDateTime.now(), id);

        files.forEach(storage::delete);
        log.info("Deleted account {} ({})", id, role);
    }
}
