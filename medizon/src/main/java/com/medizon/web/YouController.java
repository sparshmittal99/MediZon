package com.medizon.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The Android app's "You" tab: the account menu (the website keeps it in the header's account dropdown) with sign out.
 * Opened outside the app it's just the account page.
 */
@Controller
public class YouController {

    @GetMapping("/you")
    public String you(HttpServletRequest request) {
        return GlobalModelAdvice.isApp(request) ? "you" : "redirect:/account";
    }
}
