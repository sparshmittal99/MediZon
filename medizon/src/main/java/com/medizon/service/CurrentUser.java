package com.medizon.service;

import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final UserRepository users;

    public Optional<User> find() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) return Optional.empty();
        return users.findByEmailIgnoreCase(a.getName());
    }

    public User get() {
        return find().orElseThrow(() -> new IllegalStateException("No signed-in user"));
    }
}
