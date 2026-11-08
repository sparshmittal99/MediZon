package com.medizon.web;

import com.medizon.service.AddressService;
import com.medizon.service.CartService;
import com.medizon.service.MemberService;
import com.medizon.service.OrderSettingsService;
import com.medizon.service.ReturnService;
import com.medizon.service.ReturnSettingsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Help centre: answers to common questions about each feature, open to everyone. */
@Controller
@RequiredArgsConstructor
public class HelpController {

    private final ReturnSettingsService returnSettings;
    private final OrderSettingsService orderSettings;

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("freeAbove", CartService.FREE_DELIVERY_ABOVE);
        model.addAttribute("deliveryFee", CartService.DELIVERY_FEE);
        // What each way to pay costs, as the payment page shows it: "Free", "₹10 + GST"
        java.util.Map<String, String> charges = new java.util.LinkedHashMap<>();
        for (com.medizon.domain.PaymentMethod m : java.util.List.of(com.medizon.domain.PaymentMethod.UPI, com.medizon.domain.PaymentMethod.CARD,
                com.medizon.domain.PaymentMethod.NETBANKING, com.medizon.domain.PaymentMethod.COD)) {
            charges.put(m.getLabel(), orderSettings.paymentFeeLabel(m));
        }
        model.addAttribute("paymentCharges", charges);
        model.addAttribute("returnDays", returnSettings.get().getReturnWindowDays());
        model.addAttribute("maxReturnImages", ReturnService.MAX_IMAGES);
        model.addAttribute("maxFamily", MemberService.MAX_FAMILY);
        model.addAttribute("maxAddresses", AddressService.MAX_ADDRESSES);
        return "help";
    }
}
