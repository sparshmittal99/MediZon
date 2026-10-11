package com.medizon.service;

import com.medizon.domain.Role;
import com.medizon.domain.User;
import com.medizon.dto.AdminForm;
import com.medizon.dto.ProfileForm;
import com.medizon.dto.RegisterForm;
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
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionRegistry sessionRegistry;

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
        user.setAddressLine(form.getAddressLine());
        user.setCity(form.getCity());
        user.setState(form.getState());
        user.setPincode(form.getPincode());
        return users.save(user);
    }

    @Transactional
    public void recordLogin(String email) {
        users.findByEmailIgnoreCase(email).ifPresent(u -> {
            u.setLastLoginAt(LocalDateTime.now());
            u.setLoginCount(u.getLoginCount() + 1);
        });
    }

    // ------------------------------------------------------------------ admin: manage accounts

    public List<User> all() { return users.findAllByOrderByCreatedAtDesc(); }

    public User get(Long id) { return users.findById(id).orElseThrow(() -> new BusinessException("User not found.")); }

    @Transactional
    public User setEnabled(Long id, boolean enabled, User actingAdmin) {
        User u = guardedTarget(id, actingAdmin);
        u.setEnabled(enabled);
        if (!enabled) signOutEverywhere(u);
        return u;
    }

    /** Admin accounts are separate from customer accounts and can only be created by an existing admin. */
    @Transactional
    public User createAdmin(AdminForm form, User actingAdmin) {
        if (!actingAdmin.isAdmin()) throw new BusinessException("Only an admin can create admin accounts.");
        String email = form.getEmail().trim().toLowerCase();
        if (users.existsByEmailIgnoreCase(email)) {
            throw new BusinessException("An account with this email already exists. Admin accounts need their own email.");
        }
        User u = new User();
        u.setFullName(form.getFullName().trim());
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(form.getPassword()));
        u.setRole(Role.ADMIN);
        return users.save(u);
    }

    /** Ends every session of this user; their next click lands on the sign-in page. */
    public int signOutEverywhere(User u) {
        List<SessionInformation> sessions = sessionsOf(u.getEmail());
        sessions.forEach(SessionInformation::expireNow);
        return sessions.size();
    }

    private User guardedTarget(Long id, User actingAdmin) {
        if (id.equals(actingAdmin.getId())) throw new BusinessException("You can't change your own account here.");
        return get(id);
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
