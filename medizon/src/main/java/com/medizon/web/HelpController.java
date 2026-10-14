package com.medizon.web;

import com.medizon.service.AddressService;
import com.medizon.service.CartService;
import com.medizon.service.MemberService;
import com.medizon.service.ReturnService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Help centre: answers to common questions about each feature, open to everyone. */
@Controller
public class HelpController {

    @GetMapping("/help")
    public String help(Model model) {
        model.addAttribute("freeAbove", CartService.FREE_DELIVERY_ABOVE);
        model.addAttribute("deliveryFee", CartService.DELIVERY_FEE);
        model.addAttribute("returnDays", ReturnService.RETURN_WINDOW_DAYS);
        model.addAttribute("maxReturnImages", ReturnService.MAX_IMAGES);
        model.addAttribute("maxFamily", MemberService.MAX_FAMILY);
        model.addAttribute("maxAddresses", AddressService.MAX_ADDRESSES);
        return "help";
    }
}
