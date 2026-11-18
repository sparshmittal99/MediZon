package com.medizon.web;

import com.medizon.service.AddressService;
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
    private final com.medizon.service.PaymentService paymentService;

    @GetMapping("/help")
    public String help(Model model) {
        com.medizon.domain.OrderSettings os = orderSettings.get();
        model.addAttribute("freeAbove", OrderSettingsService.plain(os.getFreeDeliveryAbove()));
        model.addAttribute("deliveryFee", OrderSettingsService.plain(os.getDeliveryFee()));
        model.addAttribute("minOrder", OrderSettingsService.plain(os.getMinOrderValue()));
        // What each way to pay costs, as the payment page shows it: "Free", "₹10 + GST"
        java.util.Map<String, String> charges = new java.util.LinkedHashMap<>();
        // Only the ways to pay that are switched on right now (online methods depend on the payment gateways set up)
        java.util.List<com.medizon.domain.PaymentMethod> online = paymentService.onlineMethods();
        for (com.medizon.domain.PaymentMethod m : java.util.List.of(com.medizon.domain.PaymentMethod.UPI, com.medizon.domain.PaymentMethod.CARD,
                com.medizon.domain.PaymentMethod.NETBANKING, com.medizon.domain.PaymentMethod.COD)) {
            if (m.isOnDelivery() || online.contains(m)) charges.put(m.getLabel(), orderSettings.paymentFeeLabel(m));
        }
        model.addAttribute("deliveryAlwaysFree", os.isDeliveryAlwaysFree());
        model.addAttribute("paymentCharges", charges);
        com.medizon.domain.ReturnSettings rs = returnSettings.get();
        model.addAttribute("returnDays", rs.getReturnWindowDays());
        model.addAttribute("pickupMin", rs.getPickupMinDays());
        model.addAttribute("pickupMax", rs.getPickupMaxDays());
        model.addAttribute("upiRefundDays", rs.getUpiRefundDays());
        model.addAttribute("bankRefundDays", rs.getBankRefundDays());
        model.addAttribute("cardRefundDays", rs.getCardRefundDays());
        model.addAttribute("maxRxFiles", com.medizon.service.PrescriptionService.MAX_FILES);
        model.addAttribute("maxReturnImages", ReturnService.MAX_IMAGES);
        model.addAttribute("maxFamily", MemberService.MAX_FAMILY);
        model.addAttribute("maxAddresses", AddressService.MAX_ADDRESSES);
        return "help";
    }
}
