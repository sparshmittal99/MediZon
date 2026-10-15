package com.medizon.config;

import com.medizon.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AppUserDetailsService implements UserDetailsService {

    private final UserRepository users;

    @Override
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        com.medizon.domain.User u = users.findByEmailIgnoreCase(email.trim())
                .orElseThrow(() -> new UsernameNotFoundException("No account for " + email));
        // Staff accounts also get STAFF: pages that are for any back-office account (and not for shoppers) check that
        String[] roles = u.isStaff() ? new String[]{u.getRole().name(), "STAFF"} : new String[]{u.getRole().name()};
        return User.withUsername(u.getEmail())
                .password(u.getPassword())
                .roles(roles)
                .disabled(!u.isEnabled() || u.isDeleted())
                .build();
    }
}
