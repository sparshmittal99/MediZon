package com.medizon.dto;

import com.medizon.domain.RefundMode;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;

/** The second step of a return request: where the packs are collected from and where the refund goes. */
@Data
public class ReturnPickupForm {

    /** Pick up from the address the order was delivered to. */
    public static final String ORDER_ADDRESS = "order";
    /** Pick up from an address typed on the form. */
    public static final String NEW_ADDRESS = "new";

    /** {@link #ORDER_ADDRESS}, {@link #NEW_ADDRESS} or the id of a saved address. */
    private String pickupFrom = ORDER_ADDRESS;

    /** Filled when {@link #pickupFrom} is {@link #NEW_ADDRESS}. */
    private AddressForm newAddress = new AddressForm();
    /** Also save the typed address to the account. */
    private boolean saveAddress;

    /** The date shown on the form, kept if it's still a date we can pick up on. */
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
    private LocalDate pickupDate;

    /** Only asked for cash on delivery orders; prepaid orders are refunded to the payment method used. */
    private RefundMode refundMode;
    private String upiId;
    private String accountName;
    private String accountNumber;
    private String confirmAccountNumber;
    private String ifsc;
}
