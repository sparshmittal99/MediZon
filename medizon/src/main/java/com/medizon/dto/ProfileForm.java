package com.medizon.dto;

import com.medizon.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ProfileForm {
    @NotBlank(message = "Enter your name")
    private String fullName;

    @Pattern(regexp = "^$|^[0-9+ ]{10,15}$", message = "Enter a 10 digit mobile number")
    private String phone;

    private String addressLine;
    private String city;
    private String state;

    @Pattern(regexp = "^$|^[0-9]{6}$", message = "PIN code has 6 digits")
    private String pincode;

    public static ProfileForm from(User u) {
        ProfileForm f = new ProfileForm();
        f.setFullName(u.getFullName());
        f.setPhone(u.getPhone());
        f.setAddressLine(u.getAddressLine());
        f.setCity(u.getCity());
        f.setState(u.getState());
        f.setPincode(u.getPincode());
        return f;
    }
}
