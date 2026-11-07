package com.medizon.web;

import com.medizon.domain.Announcement;
import com.medizon.domain.AnnouncementKind;
import com.medizon.domain.MaintenanceWindow;
import com.medizon.service.AnnouncementService;
import com.medizon.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.time.LocalDateTime;
import java.util.stream.Collectors;

/**
 * Admin > Announcements: sales, discounts, updates and other messages to every customer by email, notification or
 * both, and scheduled maintenance (always both; see {@link AdminMaintenanceController}). (/admin/** is admin-only.)
 */
@Controller
@RequestMapping("/admin/announcements")
@RequiredArgsConstructor
public class AdminAnnouncementController {

    private final AnnouncementService announcements;
    private final MaintenanceService maintenance;

    @GetMapping
    public String page(Model model) {
        var windows = maintenance.recent();
        model.addAttribute("kinds", AnnouncementKind.values());
        model.addAttribute("announcements", announcements.recent());
        model.addAttribute("windows", windows);
        model.addAttribute("labels", windows.stream().collect(Collectors.toMap(MaintenanceWindow::getId, MaintenanceService::when)));
        model.addAttribute("now", LocalDateTime.now());
        model.addAttribute("active", "announcements");
        return "admin/announcements";
    }

    @PostMapping
    public String send(@RequestParam(required = false) AnnouncementKind kind, @RequestParam(required = false) String title,
                       @RequestParam(required = false) String message, @RequestParam(required = false) String promoCode,
                       @RequestParam(required = false) String link,
                       @RequestParam(defaultValue = "false") boolean viaEmail,
                       @RequestParam(defaultValue = "false") boolean viaNotification,
                       Principal principal, RedirectAttributes ra) {
        Announcement a = announcements.send(kind, title, message, promoCode, link, viaEmail, viaNotification,
                principal == null ? null : principal.getName());
        String how = a.isViaEmail() && a.isViaNotification() ? "by email and notification"
                : a.isViaEmail() ? "by email" : "by notification";
        ra.addFlashAttribute("success", "\"" + a.getTitle() + "\" sent " + how + " to " + a.getRecipients() + " customer(s).");
        return "redirect:/admin/announcements";
    }
}
