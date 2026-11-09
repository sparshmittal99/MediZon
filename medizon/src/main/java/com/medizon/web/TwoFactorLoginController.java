package com.medizon.web;

import com.medizon.config.SignInFlow;
import com.medizon.domain.User;
import com.medizon.repository.UserRepository;
import com.medizon.service.TwoFactorService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.util.Optional;

/**
 * The second step of signing in when the account has 2FA on: the 6-digit code from the authenticator app (or a backup
 * code), with "Don't ask again on this device".
 */
@Controller
@RequestMapping("/login/verify")
@RequiredArgsConstructor
public class TwoFactorLoginController {

    private final SignInFlow flow;
    private final TwoFactorService twoFactor;
    private final UserRepository users;

    @GetMapping
    public String form(Authentication auth, HttpServletRequest request, Model model) {
        if (signedIn(auth)) return "redirect:/";
        SignInFlow.Pending p = flow.pending(request);
        if (p == null) return "redirect:/login?expired";
        model.addAttribute("email", p.email());
        return "auth/two-factor";
    }

    @PostMapping
    public String verify(@RequestParam(required = false) String code,
                         @RequestParam(required = false, defaultValue = "false") boolean trust,
                         HttpServletRequest request, HttpServletResponse response, Model model) throws IOException {
        SignInFlow.Pending p = flow.pending(request);
        if (p == null) return "redirect:/login?expired";
        if (flow.locked(p, request)) {
            flow.cancel(request);
            return "redirect:/login?locked";
        }
        Optional<User> user = users.findByEmailIgnoreCase(p.email());
        TwoFactorService.Check check = user.map(u -> twoFactor.check(u, code)).orElse(TwoFactorService.Check.WRONG);
        if (check == TwoFactorService.Check.WRONG) {
            int left = flow.wrongCode(request, p);
            if (left == 0) return "redirect:/login?codeLocked";
            model.addAttribute("email", p.email());
            model.addAttribute("trust", trust);
            model.addAttribute("error", "That code isn't right. " + left + (left == 1 ? " try" : " tries") + " left.");
            return "auth/two-factor";
        }
        flow.codeAccepted(request, response, p, trust);
        return null;    // the flow has sent the browser on
    }

    /** "Use another account": back to the sign-in form. */
    @GetMapping("/cancel")
    public String cancel(HttpServletRequest request) {
        flow.cancel(request);
        return "redirect:/login";
    }

    private static boolean signedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
