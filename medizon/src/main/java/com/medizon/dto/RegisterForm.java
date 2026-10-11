package com.medizon.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterForm {
    @NotBlank(message = "Enter your name")
    private String fullName;

    @NotBlank(message = "Enter your email")
    @Email(message = "Enter a valid email")
    private String email;

    @Pattern(regexp = "^$|^[0-9+ ]{10,15}$", message = "Enter a 10 digit mobile number")
    private String phone;

    @Size(min = 6, message = "Use at least 6 characters")
    private String password;
}
