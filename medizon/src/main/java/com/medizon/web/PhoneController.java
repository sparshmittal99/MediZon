package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.dto.PhoneForm;
import com.medizon.service.CurrentUser;
import com.medizon.service.UserService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** "Add your mobile number": required before a customer without one can use the shop (PhoneRequiredInterceptor). */
@Controller
@RequestMapping("/account/phone")
@RequiredArgsConstructor
public class PhoneController {

    private final CurrentUser currentUser;
    private final UserService userService;

    @GetMapping
    public String form(@RequestParam(required = false) String next, Model model) {
        User u = currentUser.get();
        if (!u.isCustomer() || (u.getPhone() != null && !u.getPhone().isBlank())) {
            return "redirect:" + PhoneRequiredInterceptor.safeNext(next);
        }
        model.addAttribute("form", new PhoneForm());
        model.addAttribute("next", PhoneRequiredInterceptor.safeNext(next));
        return "auth/add-phone";
    }

    @PostMapping
    public String save(@Valid @ModelAttribute("form") PhoneForm form, BindingResult result,
                       @RequestParam(required = false) String next, HttpSession session, Model model) {
        String target = PhoneRequiredInterceptor.safeNext(next);
        if (result.hasErrors()) {
            model.addAttribute("next", target);
            return "auth/add-phone";
        }
        User u = userService.savePhone(currentUser.get(), form.getPhone());
        session.setAttribute(PhoneRequiredInterceptor.OK, u.getId());
        return "redirect:" + target;
    }
}
