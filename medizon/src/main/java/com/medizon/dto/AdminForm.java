package com.medizon.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** A new admin account, created by an existing admin. */
@Data
public class AdminForm {
    @NotBlank(message = "Enter a name")
    private String fullName;

    @NotBlank(message = "Enter an email")
    @Email(message = "Enter a valid email")
    private String email;

    @Size(min = 8, message = "Use at least 8 characters")
    private String password;
}
