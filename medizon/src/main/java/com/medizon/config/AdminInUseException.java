package com.medizon.config;

import org.springframework.security.authentication.AccountStatusException;

/**
 * The admin account is signed in on another device, and it may only be signed in on one at a time.
 * An AccountStatusException, so no other authentication provider gets a second try at the sign-in.
 */
public class AdminInUseException extends AccountStatusException {
    public AdminInUseException() {
        super("The admin account is signed in on another device");
    }
}
