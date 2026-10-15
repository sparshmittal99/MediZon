package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.AccountDeletionService;
import com.medizon.service.CurrentUser;
import com.medizon.service.UserService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Account settings: sign-in details, password, devices, and for customers, deleting the account. */
@Controller
@RequestMapping("/account")
@RequiredArgsConstructor
public class AccountController {

    private final UserService userService;
    private final AccountDeletionService accountDeletion;
    private final CurrentUser currentUser;

    @GetMapping
    public String view(Model model) {
        User u = currentUser.get();
        model.addAttribute("sessions", userService.sessionCount(u));
        model.addAttribute("minPassword", UserService.MIN_PASSWORD);
        return "account";
    }

    @PostMapping("/password")
    public String changePassword(@RequestParam String currentPassword, @RequestParam String newPassword,
                                 @RequestParam String confirmPassword, HttpSession session, RedirectAttributes ra) {
        int others = userService.changePassword(currentUser.get(), currentPassword, newPassword, confirmPassword, session.getId());
        ra.addFlashAttribute("success", "Password changed." + (others > 0
                ? " You were signed out on " + others + " other device" + (others == 1 ? "." : "s.") : ""));
        return "redirect:/account";
    }

    /**
     * A customer deletes their own account, confirming with their password. Personal data goes; orders and invoices
     * stay for the accounts, without the personal details (see AccountDeletionService). Then they're signed out.
     */
    @PostMapping("/delete")
    public String delete(@RequestParam String password, HttpServletRequest request, RedirectAttributes ra) throws ServletException {
        accountDeletion.deleteOwn(currentUser.get(), password);
        request.logout();
        ra.addFlashAttribute("success", "Your account is deleted. Thank you for using MediZon.");
        return "redirect:/";
    }

    @PostMapping("/sign-out-others")
    public String signOutOthers(HttpSession session, RedirectAttributes ra) {
        int n = userService.signOutOtherSessions(currentUser.get(), session.getId());
        ra.addFlashAttribute("success", n == 0 ? "You're not signed in anywhere else."
                : "Signed out on " + n + " other device" + (n == 1 ? "." : "s."));
        return "redirect:/account";
    }
}
