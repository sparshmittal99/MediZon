package com.medizon.service;

import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Which MediZon account a Google account signs in to. The account it signed in to before; else the customer account
 * with the same email, which is linked to it (Google has checked the email is theirs); else a new customer account
 * with the name and email from Google. Staff accounts keep signing in with their password.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleAccountService {

    /** Why Google sign-in was refused; the sign-in page shows a message for each (?google=...). */
    public enum Refusal { UNVERIFIED, STAFF, DISABLED, FAILED }

    public static class Refused extends RuntimeException {
        private final Refusal reason;
        public Refused(Refusal reason) { super(reason.name()); this.reason = reason; }
        public Refusal reason() { return reason; }
    }

    /** The account, and whether it was just made (no new-device email; the welcome email went out). */
    public record Result(User user, boolean created) {}

    private final UserRepository users;
    private final UserService userService;

    @Transactional
    public Result resolve(String subject, String email, boolean emailVerified, String name) {
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) throw new Refused(Refusal.FAILED);
        if (!emailVerified) throw new Refused(Refusal.UNVERIFIED);
        String cleanEmail = email.trim().toLowerCase();

        Optional<User> bySubject = users.findByGoogleSubject(subject);
        if (bySubject.isPresent()) return new Result(usable(bySubject.get()), false);

        Optional<User> byEmail = users.findByEmailIgnoreCase(cleanEmail);
        if (byEmail.isPresent()) {
            User u = usable(byEmail.get());
            if (u.getGoogleSubject() != null && !u.getGoogleSubject().equals(subject)) throw new Refused(Refusal.FAILED);
            u.setGoogleSubject(subject);
            users.save(u);
            log.info("Account {} linked to its Google account", u.getId());
            return new Result(u, false);
        }

        String fullName = name == null || name.isBlank() ? cleanEmail.substring(0, cleanEmail.indexOf('@')) : name.trim();
        if (fullName.length() > 100) fullName = fullName.substring(0, 100);
        User created = userService.registerWithGoogle(fullName, cleanEmail, subject);
        log.info("Account {} created with Sign in with Google", created.getId());
        return new Result(created, true);
    }

    private static User usable(User u) {
        if (u.isDeleted() || !u.isEnabled()) throw new Refused(Refusal.DISABLED);
        if (!u.isCustomer()) throw new Refused(Refusal.STAFF);
        return u;
    }
}
