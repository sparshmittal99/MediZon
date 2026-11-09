package com.medizon.web;

import com.medizon.config.MediZonRememberMeServices;
import com.medizon.domain.SecureAccountToken;
import com.medizon.service.BusinessException;
import com.medizon.service.SecureAccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

/**
 * "This wasn't me" from a new sign-in email. Opening the link only shows a page: mail apps and link scanners open
 * links on their own, and that mustn't sign anyone out. The one button on it does the work.
 */
@Controller
@RequestMapping("/account/secure")
@RequiredArgsConstructor
public class SecureAccountController {

    private final SecureAccountService secureAccount;
    private final MediZonRememberMeServices rememberMe;

    @GetMapping
    public String confirm(@RequestParam(required = false) String token, Model model) {
        Optional<SecureAccountToken> t = secureAccount.find(token);
        model.addAttribute("valid", t.isPresent());
        t.ifPresent(found -> {
            model.addAttribute("token", token);
            model.addAttribute("device", found.getDeviceLabel());
            model.addAttribute("signedInAt", found.getCreatedAt());
            model.addAttribute("email", found.getUser().getEmail());
        });
        return "auth/secure-account";
    }

    @PostMapping
    public String secure(@RequestParam String token, HttpServletRequest request, HttpServletResponse response,
                         Model model, RedirectAttributes ra) {
        SecureAccountService.Secured done;
        try {
            done = secureAccount.secure(token);
        } catch (BusinessException e) {
            model.addAttribute("valid", false);
            model.addAttribute("error", e.getMessage());
            return "auth/secure-account";
        }
        // This browser is signed out too (it may be signed in to the account, or another one)
        rememberMe.loginFail(request, response);
        new SecurityContextLogoutHandler().logout(request, response, SecurityContextHolder.getContext().getAuthentication());
        if (done.resetToken() == null) {
            model.addAttribute("secured", true);
            model.addAttribute("staff", true);
            return "auth/secure-account";
        }
        ra.addAttribute("token", done.resetToken());
        ra.addFlashAttribute("secured", true);
        return "redirect:/reset-password";
    }
}
