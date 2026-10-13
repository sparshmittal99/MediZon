package com.medizon.dto;

import com.medizon.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/** Name and mobile number. Delivery addresses are managed in the address book ({@code /addresses}). */
@Data
public class ProfileForm {
    @NotBlank(message = "Enter your name")
    private String fullName;

    @Pattern(regexp = "^$|^[0-9+ ]{10,15}$", message = "Enter a 10 digit mobile number")
    private String phone;

    public static ProfileForm from(User u) {
        ProfileForm f = new ProfileForm();
        f.setFullName(u.getFullName());
        f.setPhone(u.getPhone());
        return f;
    }
}
