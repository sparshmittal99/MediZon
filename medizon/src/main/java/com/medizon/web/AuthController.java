package com.medizon.web;

import com.medizon.dto.RegisterForm;
import com.medizon.service.BusinessException;
import com.medizon.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    /** Sign-in and sign-up pages keep the header simple: no category bar. */
    @ModelAttribute("hideCatbar")
    boolean hideCatbar() { return true; }

    /**
     * Someone already signed in (back button after signing in, an old tab, a bookmark) goes to their home page
     * instead of a sign-in form under a "Hello, name" header.
     */
    @GetMapping("/login")
    public String login(Authentication auth) {
        return signedIn(auth) ? home(auth) : "auth/login";
    }

    @GetMapping("/register")
    public String registerForm(Authentication auth, Model model) {
        if (signedIn(auth)) return home(auth);
        model.addAttribute("form", new RegisterForm());
        return "auth/register";
    }

    @PostMapping("/register")
    public String register(@Valid @ModelAttribute("form") RegisterForm form, BindingResult result, RedirectAttributes ra) {
        if (result.hasErrors()) return "auth/register";
        try {
            userService.register(form);
        } catch (BusinessException e) {
            result.rejectValue("email", "exists", e.getMessage());
            return "auth/register";
        }
        ra.addFlashAttribute("success", "Account created. Sign in to continue.");
        return "redirect:/login";
    }

    private static boolean signedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }

    /** Staff land on the back office, customers on the shop, as after signing in. */
    private static String home(Authentication auth) {
        boolean staff = auth.getAuthorities().stream().anyMatch(a -> "ROLE_STAFF".equals(a.getAuthority()));
        return staff ? "redirect:/admin" : "redirect:/";
    }
}
