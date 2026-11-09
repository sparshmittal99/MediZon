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

    /** Required: Razorpay checkout and delivery partners need a number to reach the customer. */
    @NotBlank(message = "Enter your mobile number")
    @Pattern(regexp = PhoneForm.PATTERN, message = PhoneForm.MESSAGE)
    private String phone;

    @Size(min = 6, message = "Use at least 6 characters")
    private String password;
}
