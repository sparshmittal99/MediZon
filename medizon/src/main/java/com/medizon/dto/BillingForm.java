package com.medizon.dto;

import com.medizon.domain.BillingAddress;
import com.medizon.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BillingForm {

    @NotBlank(message = "Enter the name to bill")
    @Size(max = 80, message = "Keep the name under 80 characters")
    private String name;

    @NotBlank(message = "Enter the address")
    @Size(max = 300, message = "Keep the address under 300 characters")
    private String addressLine;

    @NotBlank(message = "Enter the city")
    @Size(max = 80)
    private String city;

    @NotBlank(message = "Enter the state")
    @Size(max = 60)
    private String state;

    @NotBlank(message = "Enter the PIN code")
    @Pattern(regexp = "^\\s*[1-9][0-9]{2}\\s?[0-9]{3}\\s*$", message = "PIN code has 6 digits")
    private String pincode;

    /** 15 characters: 2 digit state code, PAN, entity number, Z, check character. */
    @Pattern(regexp = "^$|^\\s*[0-9]{2}[A-Za-z]{5}[0-9]{4}[A-Za-z][0-9A-Za-z][Zz][0-9A-Za-z]\\s*$", message = "Enter a valid 15 character GSTIN")
    private String gstin;

    public static BillingForm from(User u) {
        BillingForm f = new BillingForm();
        BillingAddress b = u.getBilling();
        if (b != null && b.isSet()) {
            f.setName(b.getName());
            f.setAddressLine(b.getAddressLine());
            f.setCity(b.getCity());
            f.setState(b.getState());
            f.setPincode(b.getPincode());
            f.setGstin(b.getGstin());
        } else {
            f.setName(u.getFullName());
        }
        return f;
    }
}
