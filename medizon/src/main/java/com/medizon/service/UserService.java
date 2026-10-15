package com.medizon.service;

import com.medizon.domain.BillingAddress;
import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.dto.NewUserForm;
import com.medizon.dto.BillingForm;
import com.medizon.dto.ProfileForm;
import com.medizon.dto.RegisterForm;
import com.medizon.repository.StoreRepository;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;
    private final StoreRepository stores;

    @Transactional
    public User register(RegisterForm form) {
        String email = form.getEmail().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("An account with this email already exists.");
        }
        User u = new User();
        u.setFullName(form.getFullName().trim());
        u.setEmail(email);
        u.setPhone(form.getPhone());
        u.setPassword(passwordEncoder.encode(form.getPassword()));
        u.setRole(Role.CUSTOMER);
        return users.save(u);
    }

    @Transactional
    public User updateProfile(User user, ProfileForm form) {
        user.setFullName(form.getFullName().trim());
        user.setPhone(form.getPhone());
        return users.save(user);
    }

    @Transactional
    public User saveBillingAddress(User user, BillingForm form) {
        BillingAddress b = new BillingAddress();
        b.setName(form.getName().trim());
        b.setAddressLine(form.getAddressLine().trim());
        b.setCity(form.getCity().trim());
        b.setState(form.getState().trim());
        b.setPincode(DeliveryAreaService.normalize(form.getPincode()));
        b.setGstin(form.getGstin() == null || form.getGstin().isBlank() ? null : form.getGstin().trim().toUpperCase());
        user.setBilling(b);
        return users.save(user);
    }

    /** Back to billing each order to the address it's delivered to. */
    @Transactional
    public User removeBillingAddress(User user) {
        user.setBilling(null);
        return users.save(user);
    }

    @Transactional
    public Optional<User> recordLogin(String email) {
        Optional<User> user = users.findByEmailIgnoreCase(email);
        user.ifPresent(u -> {
            u.setLastLoginAt(LocalDateTime.now());
            u.setLoginCount(u.getLoginCount() + 1);
        });
        return user;
    }

    // ------------------------------------------------------------------ account settings

    public static final int MIN_PASSWORD = 6;

    /**
     * Changes the password after checking the current one, then signs the account out on every other device.
     * "Keep me signed in" cookies stop working too, since they're signed with the old password.
     */
    @Transactional
    public int changePassword(User user, String current, String next, String confirm, String keepSessionId) {
        if (user.isAdmin()) throw new BusinessException("The admin password is set with MEDIZON_ADMIN_PASSWORD on the server.");
        if (current == null || !passwordEncoder.matches(current, user.getPassword())) {
            throw new BusinessException("Your current password isn't right.");
        }
        if (next == null || next.length() < MIN_PASSWORD) {
            throw new BusinessException("Use at least " + MIN_PASSWORD + " characters for the new password.");
        }
        if (!next.equals(confirm)) throw new BusinessException("The new passwords don't match.");
        if (passwordEncoder.matches(next, user.getPassword())) {
            throw new BusinessException("Choose a password different from your current one.");
        }
        user.setPassword(passwordEncoder.encode(next));
        users.save(user);
        return signOutOtherSessions(user, keepSessionId);
    }

    /** Step one of changing the password: whether this is the account's current password. */
    public boolean checkPassword(User user, String password) {
        return password != null && passwordEncoder.matches(password, user.getPassword());
    }

    /** Ends every session of this user except the given one (the browser they're using). */
    public int signOutOtherSessions(User u, String keepSessionId) {
        List<SessionInformation> others = sessionsOf(u.getEmail()).stream()
                .filter(si -> !si.getSessionId().equals(keepSessionId)).toList();
        others.forEach(SessionInformation::expireNow);
        return others.size();
    }

    /** How many browsers or devices this user is signed in on right now. */
    public int sessionCount(User u) { return sessionsOf(u.getEmail()).size(); }

    // ------------------------------------------------------------------ admin: manage accounts

    /** Every account except deleted ones, newest first. */
    public List<User> all() { return users.findAllByOrderByCreatedAtDesc().stream().filter(u -> !u.isDeleted()).toList(); }

    public User get(Long id) { return users.findById(id).orElseThrow(() -> new BusinessException("User not found.")); }

    @Transactional
    public User setEnabled(Long id, boolean enabled, User actingAdmin) {
        User u = guardedTarget(id, actingAdmin);
        if (enabled && u.isAdmin()) throw new BusinessException("There's only one admin, set by MEDIZON_ADMIN_EMAIL. Delete this old admin account instead.");
        u.setEnabled(enabled);
        if (!enabled) signOutEverywhere(u);
        return u;
    }

    /**
     * The admin creates an account with a role: a customer, or a staff account limited to that role's work. There is
     * only ever one admin, so that role can't be given. Store accounts belong to one store.
     */
    @Transactional
    public User createUser(NewUserForm form, User actingAdmin) {
        if (!actingAdmin.isAdmin()) throw new BusinessException("Only the admin can create accounts.");
        if (!Role.assignable().contains(form.getRole())) throw new BusinessException("There's only one admin; choose another role.");
        String email = form.getEmail().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) throw new BusinessException("An account with this email already exists.");
        if (form.getPassword() == null || form.getPassword().length() < 8) {
            throw new BusinessException("Use at least 8 characters for the password.");
        }
        User u = new User();
        u.setFullName(form.getFullName().trim());
        u.setEmail(email);
        u.setPhone(form.getPhone() == null || form.getPhone().isBlank() ? null : form.getPhone().trim());
        u.setPassword(passwordEncoder.encode(form.getPassword()));
        u.setRole(form.getRole());
        if (form.getRole() == Role.STORE) {
            if (form.getStoreId() == null) throw new BusinessException("Choose the store this account works for.");
            u.setStore(stores.findById(form.getStoreId()).orElseThrow(() -> new BusinessException("That store doesn't exist.")));
        }
        return users.save(u);
    }

    /** Ends every session of this user; their next click lands on the sign-in page. */
    public int signOutEverywhere(User u) {
        List<SessionInformation> sessions = sessionsOf(u.getEmail());
        sessions.forEach(SessionInformation::expireNow);
        return sessions.size();
    }

    private User guardedTarget(Long id, User actingAdmin) {
        if (!actingAdmin.isAdmin()) throw new BusinessException("Only the admin can manage accounts.");
        if (id.equals(actingAdmin.getId())) throw new BusinessException("You can't change your own account here.");
        User u = get(id);
        if (u.isDeleted()) throw new BusinessException("That account has been deleted.");
        return u;
    }

    // ------------------------------------------------------------------ who is online

    /** Emails of users with at least one live session. */
    public Set<String> onlineEmails() {
        return sessionRegistry.getAllPrincipals().stream()
                .filter(p -> !sessionRegistry.getAllSessions(p, false).isEmpty())
                .map(UserService::emailOf)
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
    }

    private List<SessionInformation> sessionsOf(String email) {
        return sessionRegistry.getAllPrincipals().stream()
                .filter(p -> emailOf(p).equalsIgnoreCase(email))
                .flatMap(p -> sessionRegistry.getAllSessions(p, false).stream())
                .toList();
    }

    private static String emailOf(Object principal) {
        return principal instanceof UserDetails d ? d.getUsername() : String.valueOf(principal);
    }
}
