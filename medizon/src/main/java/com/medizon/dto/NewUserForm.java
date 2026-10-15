package com.medizon.dto;

import com.medizon.domain.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** An account the admin creates: a customer, or staff with one role (a store account also names its store). */
@Data
public class NewUserForm {
    @NotBlank(message = "Enter a name")
    private String fullName;

    @NotBlank(message = "Enter an email")
    @Email(message = "Enter a valid email")
    private String email;

    private String phone;

    @Size(min = 8, message = "Use at least 8 characters for the password")
    private String password;

    @NotNull(message = "Choose a role")
    private Role role;

    /** Required for {@link Role#STORE}. */
    private Long storeId;
}
