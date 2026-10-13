package com.medizon.dto;

import com.medizon.domain.Address;
import com.medizon.domain.AddressType;
import com.medizon.domain.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class AddressForm {
    private Long id;

    @NotNull
    private AddressType type = AddressType.HOME;

    @Size(max = 40, message = "Keep the name under 40 characters")
    private String customLabel;

    private boolean forSomeoneElse;

    @NotBlank(message = "Enter the receiver's name")
    @Size(max = 80, message = "Keep the name under 80 characters")
    private String receiverName;

    @NotBlank(message = "Enter a phone number for the delivery")
    @Pattern(regexp = "^\\+?[0-9 ]{10,15}$", message = "Enter a 10 digit mobile number")
    private String receiverPhone;

    @NotBlank(message = "Enter the flat, house number or building")
    @Size(max = 200)
    private String addressLine;

    @Size(max = 200)
    private String area;

    @Size(max = 120)
    private String landmark;

    @NotBlank(message = "Enter the city")
    @Size(max = 80)
    private String city;

    @Size(max = 60)
    private String state;

    @NotBlank(message = "Enter the PIN code")
    @Pattern(regexp = "^\\s*[1-9][0-9]{2}\\s?[0-9]{3}\\s*$", message = "PIN code has 6 digits")
    private String pincode;

    private boolean makeDefault;

    /** A new address for the account holder starts with their name and number. */
    public static AddressForm blank(User u) {
        AddressForm f = new AddressForm();
        f.setReceiverName(u.getFullName());
        f.setReceiverPhone(u.getPhone());
        return f;
    }

    public static AddressForm from(Address a) {
        AddressForm f = new AddressForm();
        f.setId(a.getId());
        f.setType(a.getType());
        f.setCustomLabel(a.getCustomLabel());
        f.setForSomeoneElse(a.isForSomeoneElse());
        f.setReceiverName(a.getReceiverName());
        f.setReceiverPhone(a.getReceiverPhone());
        f.setAddressLine(a.getAddressLine());
        f.setArea(a.getArea());
        f.setLandmark(a.getLandmark());
        f.setCity(a.getCity());
        f.setState(a.getState());
        f.setPincode(a.getPincode());
        f.setMakeDefault(a.isDefaultAddress());
        return f;
    }
}
