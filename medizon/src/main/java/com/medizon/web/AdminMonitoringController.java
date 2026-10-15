package com.medizon.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medizon.ai.AssistantClient;
import com.medizon.domain.AppError;
import com.medizon.monitoring.ErrorMonitor;
import com.medizon.monitoring.PerformanceMonitor;
import com.medizon.repository.AppErrorRepository;
import com.medizon.service.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDateTime;
import java.util.*;

/** Admin > Monitoring: health of each part of the system, errors grouped like an issue tracker, and performance. */
@Controller
@RequestMapping("/admin/monitoring")
@RequiredArgsConstructor
public class AdminMonitoringController {

    /** One line of the health panel. */
    public record Check(String name, String status, Map<String, Object> details) {
        public boolean isUp() { return "UP".equals(status); }
        public boolean isDown() { return "DOWN".equals(status) || "OUT_OF_SERVICE".equals(status); }
    }

    private final HealthEndpoint health;
    private final PerformanceMonitor performance;
    private final ErrorMonitor errorMonitor;
    private final AppErrorRepository errors;
    private final AssistantClient assistant;

    @GetMapping
    public String dashboard(@RequestParam(defaultValue = "open") String errorsView, Model model) {
        errorMonitor.flush();       // show what just happened, not what was stored a few seconds ago
        boolean resolved = "resolved".equals(errorsView);

        HealthComponent h = health.health();
        List<Check> checks = new ArrayList<>();
        if (h instanceof CompositeHealth c) {
            c.getComponents().forEach((name, part) -> checks.add(new Check(name, part.getStatus().getCode(),
                    part instanceof Health ph ? ph.getDetails() : Map.of())));
        }

        model.addAttribute("active", "monitoring");
        model.addAttribute("overall", h.getStatus().getCode());
        model.addAttribute("checks", checks);
        model.addAttribute("totals", performance.requestTotals());
        Map<String, Double> system = performance.system();
        model.addAttribute("system", system);
        model.addAttribute("uptime", uptime(system.get("uptimeSeconds").longValue()));
        model.addAttribute("endpoints", performance.endpoints().stream().limit(25).toList());
        model.addAttribute("slowRequests", performance.slowRequests().stream().limit(50).toList());
        model.addAttribute("slowMs", performance.getSlowThresholdMs());
        model.addAttribute("pages", performance.pageTimings().stream().limit(25).toList());
        model.addAttribute("jobs", performance.jobs());
        model.addAttribute("errorsView", resolved ? "resolved" : "open");
        model.addAttribute("errors", errors.findTop100ByResolvedOrderByLastSeenDesc(resolved));
        model.addAttribute("openErrors", errors.countByResolvedFalse());
        model.addAttribute("errorsToday", errors.countByResolvedFalseAndLastSeenAfter(LocalDateTime.now().minusHours(24)));
        model.addAttribute("assistant", assistantMetrics());
        return "admin/monitoring";
    }

    /** "3 d 4 h", "2 h 5 min", "40 s". */
    static String uptime(long seconds) {
        if (seconds >= 86_400) return seconds / 86_400 + " d " + seconds % 86_400 / 3_600 + " h";
        if (seconds >= 3_600) return seconds / 3_600 + " h " + seconds % 3_600 / 60 + " min";
        if (seconds >= 60) return seconds / 60 + " min";
        return seconds + " s";
    }

    private JsonNode assistantMetrics() {
        try {
            return assistant.metrics();
        } catch (RuntimeException e) {
            return null;     // the health panel already says it's unreachable
        }
    }

    @PostMapping("/errors/{id}/resolve")
    @Transactional
    public String resolve(@PathVariable Long id, RedirectAttributes ra) {
        AppError e = find(id);
        e.setResolved(true);
        e.setResolvedAt(LocalDateTime.now());
        ra.addFlashAttribute("success", "Marked as resolved. It reopens if it happens again.");
        return "redirect:/admin/monitoring#errors";
    }

    @PostMapping("/errors/{id}/reopen")
    @Transactional
    public String reopen(@PathVariable Long id, RedirectAttributes ra) {
        AppError e = find(id);
        e.setResolved(false);
        e.setResolvedAt(null);
        ra.addFlashAttribute("success", "Reopened.");
        return "redirect:/admin/monitoring?errorsView=resolved#errors";
    }

    @PostMapping("/errors/resolve-all")
    @Transactional
    public String resolveAll(RedirectAttributes ra) {
        int n = errors.resolveAll(LocalDateTime.now());
        ra.addFlashAttribute("success", n == 1 ? "1 error marked as resolved." : n + " errors marked as resolved.");
        return "redirect:/admin/monitoring#errors";
    }

    private AppError find(Long id) {
        return errors.findById(id).orElseThrow(() -> new BusinessException("That error is no longer on record."));
    }
}
