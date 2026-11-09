package com.medizon.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** The mobile number asked for after Sign in with Google, or from an account that doesn't have one yet. */
@Data
public class PhoneForm {
    /** The same rule as everywhere else a mobile number is typed (what Razorpay's checkout is given). */
    public static final String PATTERN = "^[0-9+ ]{10,15}$";
    public static final String MESSAGE = "Enter a 10 digit mobile number";

    @NotBlank(message = "Enter your mobile number")
    @Pattern(regexp = PATTERN, message = MESSAGE)
    private String phone;
}
