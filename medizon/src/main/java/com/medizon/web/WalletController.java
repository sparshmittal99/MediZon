package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** MediZon Money: the balance and every refund paid in and order paid with it. */
@Controller
@RequiredArgsConstructor
public class WalletController {

    private final WalletService wallet;
    private final CurrentUser currentUser;

    @GetMapping("/wallet")
    public String wallet(Model model) {
        User user = currentUser.get();
        model.addAttribute("balance", wallet.balance(user));
        model.addAttribute("history", wallet.history(user));
        return "wallet";
    }
}
