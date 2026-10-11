package com.medizon.web;

import com.medizon.dto.RegisterForm;
import com.medizon.service.BusinessException;
import com.medizon.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @GetMapping("/login")
    public String login() { return "auth/login"; }

    @GetMapping("/register")
    public String registerForm(Model model) {
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
}
