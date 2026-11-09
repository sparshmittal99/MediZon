package com.medizon.web;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.Totp;
import com.medizon.service.TwoFactorService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

/**
 * Account settings > Two-step verification: turning it on with an authenticator app (scan the QR code, enter a code),
 * backup codes, and turning it off. Every account can use it, staff and admin included.
 */
@Controller
@RequestMapping("/account/two-factor")
@RequiredArgsConstructor
public class TwoFactorController {

    /** Session: the secret being set up, until a code from the app confirms it. */
    static final String SETUP = "mz.2fa.setup";

    private final CurrentUser currentUser;
    private final TwoFactorService twoFactor;

    @GetMapping("/setup")
    public String setup(HttpSession session, Model model) {
        User u = currentUser.get();
        if (u.isTwoFactorEnabled()) return "redirect:/account#two-factor";
        String secret = (String) session.getAttribute(SETUP);
        if (secret == null) {
            secret = Totp.newSecret();
            session.setAttribute(SETUP, secret);
        }
        model.addAttribute("setup", twoFactor.setup(u, secret));
        return "account-two-factor";
    }

    @PostMapping("/enable")
    public String enable(@RequestParam(required = false) String code, HttpSession session, RedirectAttributes ra) {
        List<String> codes = twoFactor.enable(currentUser.get(), (String) session.getAttribute(SETUP), code);
        session.removeAttribute(SETUP);
        ra.addFlashAttribute("backupCodes", codes);
        ra.addFlashAttribute("justEnabled", true);
        return "redirect:/account/two-factor/backup-codes";
    }

    /** The backup codes, shown once right after they're made. */
    @GetMapping("/backup-codes")
    public String backupCodes(Model model) {
        if (!model.containsAttribute("backupCodes")) return "redirect:/account#two-factor";
        return "account-backup-codes";
    }

    @PostMapping("/backup-codes")
    public String newBackupCodes(@RequestParam(required = false) String code, RedirectAttributes ra) {
        ra.addFlashAttribute("backupCodes", twoFactor.newBackupCodes(currentUser.get(), code));
        return "redirect:/account/two-factor/backup-codes";
    }

    @PostMapping("/disable")
    public String disable(@RequestParam(required = false) String code, RedirectAttributes ra) {
        twoFactor.disable(currentUser.get(), code);
        ra.addFlashAttribute("success", "Two-step verification is off. Signing in asks for your password or Google account only.");
        return "redirect:/account#two-factor";
    }
}
