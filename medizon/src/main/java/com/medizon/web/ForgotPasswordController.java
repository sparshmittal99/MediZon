package com.medizon.web;

import com.medizon.domain.PasswordResetToken;
import com.medizon.domain.User;
import com.medizon.service.BusinessException;
import com.medizon.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Controller
@RequiredArgsConstructor
public class ForgotPasswordController {

    private final PasswordResetService passwordResetService;

    // Step 1: Request verification code by email
    @GetMapping("/forgot-password")
    public String forgotPasswordForm(@RequestParam(required = false) String email, Authentication auth, Model model) {
        if (signedIn(auth)) return "redirect:/";
        if (email != null && !email.isBlank()) {
            model.addAttribute("email", email.trim());
        }
        return "auth/forgot-password";
    }

    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email, RedirectAttributes ra) {
        if (email != null && !email.isBlank()) {
            try {
                passwordResetService.initiateReset(email);
            } catch (Exception e) {
                log.error("Error initiating password reset for {}", email, e);
            }
        }
        // Redirect to step 2: Enter and validate verification code (the resend countdown starts there)
        ra.addFlashAttribute("codeSent", true);
        ra.addAttribute("email", email != null ? email.trim() : "");
        return "redirect:/verify-code";
    }

    // Direct resend: issues a fresh code to the same email without navigating back
    @PostMapping("/resend-code")
    public String resendCode(@RequestParam String email, RedirectAttributes ra) {
        if (email != null && !email.isBlank()) {
            try {
                passwordResetService.initiateReset(email);
            } catch (Exception e) {
                log.error("Error resending verification code to {}", email, e);
            }
        }
        ra.addFlashAttribute("codeSent", true);
        ra.addAttribute("email", email != null ? email.trim() : "");
        return "redirect:/verify-code";
    }

    @PostMapping(value = "/api/auth/resend-code")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> apiResendCode(
            @RequestBody(required = false) Map<String, String> body,
            @RequestParam(required = false) String emailParam) {
        String email = (body != null && body.containsKey("email")) ? body.get("email") : emailParam;
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Email address is required."));
        }
        try {
            passwordResetService.initiateReset(email.trim());
            return ResponseEntity.ok(Map.of("success", true, "message", "A new 6-digit verification code has been sent."));
        } catch (Exception e) {
            log.error("Error resending verification code via API to {}", email, e);
            return ResponseEntity.ok(Map.of("success", true, "message", "A new 6-digit verification code has been sent."));
        }
    }

    // Step 2: Enter and validate verification code
    @GetMapping("/verify-code")
    public String verifyCodeForm(@RequestParam(required = false) String email, Authentication auth, Model model) {
        if (signedIn(auth)) return "redirect:/";
        if (email == null || email.isBlank()) {
            return "redirect:/forgot-password";
        }
        model.addAttribute("email", email.trim());
        model.addAttribute("cooldownSeconds", PasswordResetToken.RESEND_COOLDOWN_SECONDS);
        return "auth/verify-code";
    }

    @PostMapping("/verify-code")
    public String processVerifyCode(@RequestParam String email,
                                    @RequestParam String code,
                                    RedirectAttributes ra,
                                    Model model) {
        try {
            String verifiedToken = passwordResetService.verifyCode(email, code);
            // Verification succeeded! Redirect to step 3: Set new password
            ra.addAttribute("token", verifiedToken);
            return "redirect:/reset-password";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("email", email);
            model.addAttribute("cooldownSeconds", PasswordResetToken.RESEND_COOLDOWN_SECONDS);
            return "auth/verify-code";
        } catch (Exception e) {
            log.error("Error verifying code", e);
            model.addAttribute("error", "An error occurred while verifying the code. Please try again.");
            model.addAttribute("email", email);
            model.addAttribute("cooldownSeconds", PasswordResetToken.RESEND_COOLDOWN_SECONDS);
            return "auth/verify-code";
        }
    }

    // Step 3: Set new password (only accessible with verified token)
    @GetMapping("/reset-password")
    public String resetPasswordForm(@RequestParam(required = false) String token, Authentication auth, Model model) {
        if (signedIn(auth)) return "redirect:/";
        if (token == null || token.isBlank()) {
            return "redirect:/forgot-password";
        }
        Optional<User> userOpt = passwordResetService.validateToken(token);
        if (userOpt.isEmpty()) {
            return "redirect:/forgot-password";
        }
        model.addAttribute("token", token);
        model.addAttribute("email", userOpt.get().getEmail());
        return "auth/reset-password";
    }

    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam String token,
                                       @RequestParam String newPassword,
                                       @RequestParam String confirmPassword,
                                       RedirectAttributes ra,
                                       Model model) {
        try {
            passwordResetService.resetPassword(token, newPassword, confirmPassword);
            // The sign-in page shows a "Password changed" pop-up that closes itself; no banner as well
            ra.addFlashAttribute("passwordResetSuccess", true);
            return "redirect:/login";
        } catch (BusinessException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("token", token);
            return "auth/reset-password";
        } catch (Exception e) {
            log.error("Error completing password reset", e);
            model.addAttribute("error", "An unexpected error occurred. Please try again or request a new verification code.");
            model.addAttribute("token", token);
            return "auth/reset-password";
        }
    }

    private static boolean signedIn(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken);
    }
}
