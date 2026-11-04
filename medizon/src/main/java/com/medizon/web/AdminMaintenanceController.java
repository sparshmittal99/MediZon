package com.medizon.web;

import com.medizon.domain.MaintenanceWindow;
import com.medizon.service.MaintenanceService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;

/** Admin > Maintenance: announce scheduled downtime to every customer, or cancel it. (/admin/** is admin-only.) */
@Controller
@RequestMapping("/admin/maintenance")
@RequiredArgsConstructor
public class AdminMaintenanceController {

    private final MaintenanceService maintenance;

    @GetMapping
    public String page(Model model) {
        var windows = maintenance.recent();
        model.addAttribute("windows", windows);
        model.addAttribute("labels", windows.stream().collect(java.util.stream.Collectors.toMap(
                MaintenanceWindow::getId, MaintenanceService::when)));
        model.addAttribute("now", LocalDateTime.now());
        model.addAttribute("active", "maintenance");
        return "admin/maintenance";
    }

    @PostMapping
    public String schedule(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
                           RedirectAttributes ra) {
        MaintenanceWindow w = maintenance.schedule(start, end);
        ra.addFlashAttribute("success", "Downtime " + MaintenanceService.when(w) + " announced. Customers are being notified.");
        return "redirect:/admin/maintenance";
    }

    @PostMapping("/{id}/cancel")
    public String cancel(@PathVariable Long id, RedirectAttributes ra) {
        maintenance.cancel(id);
        ra.addFlashAttribute("success", "Downtime cancelled. Customers are being told.");
        return "redirect:/admin/maintenance";
    }
}
