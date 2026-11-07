package com.medizon.web;

import com.medizon.domain.MaintenanceWindow;
import com.medizon.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/**
 * Scheduled downtime, announced to every customer by notification and email, or cancelled. The page itself is the
 * Maintenance section of Admin > Announcements. (/admin/** is admin-only.)
 */
@Controller
@RequestMapping("/admin/maintenance")
@RequiredArgsConstructor
public class AdminMaintenanceController {

    private static final String PAGE = "redirect:/admin/announcements#maintenance";

    private final MaintenanceService maintenance;

    /** Old bookmarks: maintenance lives under Announcements now. */
    @GetMapping
    public String page() { return PAGE; }

    @PostMapping
    public String schedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                           RedirectAttributes ra) {
        MaintenanceWindow w = maintenance.schedule(start, end);
        ra.addFlashAttribute("success", "Downtime " + MaintenanceService.when(w) + " announced. Customers are being notified.");
        return PAGE;
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        maintenance.cancel(id);
        ra.addFlashAttribute("success", "Downtime cancelled. Customers are being told.");
        return PAGE;
    }
}
