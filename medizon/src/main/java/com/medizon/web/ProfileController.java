package com.medizon.web;

import com.medizon.dto.ProfileForm;
import com.medizon.service.CurrentUser;
import com.medizon.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserService userService;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(Model model) {
        model.addAttribute("form", ProfileForm.from(currentUser.get()));
        return "profile";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") ProfileForm form, BindingResult result,
                       @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        if (result.hasErrors()) return "profile";
        userService.updateProfile(currentUser.get(), form);
        ra.addFlashAttribute("success", "Profile saved.");
        return "redirect:" + ("checkout".equals(returnTo) ? "/checkout" : "/profile");
    }
}
