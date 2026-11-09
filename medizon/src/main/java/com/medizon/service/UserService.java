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
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
    private final NotificationService notificationService;
    private final EmailService emailService;
    private final SignInService signIns;

    @org.springframework.beans.factory.annotation.Value("${medizon.base-url:http://localhost:8080}")
    private String baseUrl;

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
        User saved = users.save(u);
        sendWelcomeEmail(saved);
        return saved;
    }

    /**
     * A customer account made by Sign in with Google: name and email from Google, no password of its own (a random one
     * nobody knows), and the mobile number asked for next (PhoneRequiredInterceptor).
     */
    @Transactional
    public User registerWithGoogle(String fullName, String email, String googleSubject) {
        User u = new User();
        u.setFullName(fullName);
        u.setEmail(email.trim().toLowerCase());
        u.setPassword(passwordEncoder.encode(java.util.UUID.randomUUID().toString()));
        u.setPasswordSet(false);
        u.setGoogleSubject(googleSubject);
        u.setRole(Role.CUSTOMER);
        User saved = users.save(u);
        sendWelcomeEmail(saved);
        return saved;
    }

    /** The mobile number asked for after Sign in with Google (or from an older account without one). */
    @Transactional
    public User savePhone(User user, String phone) {
        user.setPhone(phone.trim());
        return users.save(user);
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
    public int changePassword(User user, String current, String next, String confirm, HttpServletRequest here) {
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
        try {
            notificationService.notify(
                user,
                com.medizon.domain.NotificationType.INFO,
                "Password changed",
                "Your MediZon account password was recently changed. If you did not make this change, please contact support immediately.",
                "/account"
            );
        } catch (Exception e) {
            // non-fatal
        }
        return signOutOtherSessions(user, here);
    }

    /** Step one of changing the password: whether this is the account's current password. */
    public boolean checkPassword(User user, String password) {
        return password != null && passwordEncoder.matches(password, user.getPassword());
    }

    /**
     * Signs this user out on every browser and phone except the one making the request, remembered sign-ins included
     * ("Keep me signed in", the Android app); the caller re-issues this device's remember-me cookie.
     * Returns how many other devices were signed out.
     */
    @Transactional
    public int signOutOtherSessions(User u, HttpServletRequest here) {
        int n = signIns.endOthers(u, here);
        HttpSession session = here.getSession(false);
        String keep = session == null ? null : session.getId();
        sessionsOf(u.getEmail()).stream()
                .filter(si -> !si.getSessionId().equals(keep))
                .forEach(SessionInformation::expireNow);
        return n;
    }

    /** How many browsers and phones this user is signed in on, this one included. */
    public int sessionCount(User u) { return signIns.count(u); }

    // ------------------------------------------------------------------ admin: manage accounts

    /** Every account except deleted ones, newest first. */
    public List<User> all() { return users.findAllByOrderByCreatedAtDesc().stream().filter(u -> !u.isDeleted()).toList(); }

    public User get(Long id) { return users.findById(id).orElseThrow(() -> new NotFoundException("User not found.")); }

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
        User saved = users.save(u);
        sendWelcomeEmail(saved);
        return saved;
    }

    /**
     * The admin sets a new password for any other account, without its current one (a forgotten password), and the
     * account is signed out everywhere. The admin's own password comes from MEDIZON_ADMIN_PASSWORD.
     */
    @Transactional
    public User setPasswordByAdmin(Long id, String next, String confirm, User actingAdmin) {
        User u = guardedTarget(id, actingAdmin);
        if (u.isAdmin()) throw new BusinessException("The admin password is set with MEDIZON_ADMIN_PASSWORD on the server.");
        if (next == null || next.length() < 8) throw new BusinessException("Use at least 8 characters for the password.");
        if (!next.equals(confirm)) throw new BusinessException("The passwords don't match.");
        u.setPassword(passwordEncoder.encode(next));
        users.save(u);
        signOutEverywhere(u);
        return u;
    }

    /**
     * Ends every sign-in of this user, phones kept signed in by the app included; their next click lands on the
     * sign-in page and their phones stop ringing alarms. Returns on how many devices they were signed in.
     */
    @Transactional
    public int signOutEverywhere(User u) {
        int n = signIns.endAll(u);
        List<SessionInformation> sessions = sessionsOf(u.getEmail());
        sessions.forEach(SessionInformation::expireNow);
        return Math.max(n, sessions.size());
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

    private void sendWelcomeEmail(User user) {
        if (user == null || !user.isCustomer() || user.getEmail() == null || user.getEmail().isBlank()) return;
        String shopUrl = baseUrl.replaceAll("/+$", "") + "/medicines";
        java.util.Map<String, Object> variables = java.util.Map.of(
                "user", user,
                "shopUrl", shopUrl,
                "baseUrl", baseUrl
        );
        String text = "Hi " + user.getFirstName() + ",\n\n"
                + "Welcome to MediZon! Your account has been created.\n\n"
                + "Start exploring genuine medicines and wellness products: " + shopUrl + "\n\n"
                + "Warm regards,\nMediZon Pharmacy Team";
        emailService.sendTemplate(user.getEmail(), "Welcome to MediZon, " + user.getFirstName() + "!", "emails/welcome", variables, text);
    }
}
