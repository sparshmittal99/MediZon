package com.medizon.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.medizon.ai.AssistantClient;
import com.medizon.domain.AppError;
import com.medizon.domain.ErrorSeverity;
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

    /** One card of the health panel. */
    public record Check(String name, String status, Map<String, Object> details) {
        /** Summaries longer than this are cut short on the card, with "View more" for the rest. */
        static final int CARD_CHARS = 110;

        public boolean isUp() { return "UP".equals(status); }
        public boolean isDown() { return "DOWN".equals(status) || "OUT_OF_SERVICE".equals(status); }

        public String getLabel() {
            return switch (name) {
                case "db" -> "Database";
                case "diskSpace" -> "Disk space";
                case "assistant" -> "Assistant service";
                case "gemini" -> "Gemini (prescriptions)";
                case "groq" -> "Groq (chat)";
                default -> name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
            };
        }

        /** What the card says: the key figure when it's fine, what's wrong and its effect when it isn't. */
        public String getSummary() {
            Object free = details.get("free"), total = details.get("total");
            if ("diskSpace".equals(name) && free instanceof Number f && total instanceof Number t) {
                return String.format(Locale.ENGLISH, "%.1f GB free of %.1f GB", f.doubleValue() / 1073741824.0, t.doubleValue() / 1073741824.0);
            }
            if ("db".equals(name) && details.get("database") != null) return String.valueOf(details.get("database"));
            if (details.get("responseMs") != null) return "Answered in " + details.get("responseMs") + " ms";
            Object why = details.containsKey("error") ? details.get("error") : details.get("reason");
            if (why == null) return isUp() ? "Working normally" : "";
            return (details.containsKey("error") ? "Not answering: " : "") + why
                    + (details.get("effect") != null ? ". Meanwhile " + details.get("effect") + "." : "");
        }

        /** Whether the card has more to show than fits: anything not up, or a summary that's cut short. */
        public boolean isMore() { return !isUp() && !details.isEmpty() || getSummary().length() > CARD_CHARS; }
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
        List<Check> checks = checks(h);

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
        List<AppError> list = errors.findTop100ByResolvedOrderByLastSeenDesc(resolved);
        if (!resolved) {        // worst first, then most recent
            list = list.stream().sorted(Comparator.comparing(AppError::getSeverity, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(AppError::getLastSeen, Comparator.reverseOrder())).toList();
        }
        model.addAttribute("errors", list);
        model.addAttribute("severities", ErrorSeverity.values());
        model.addAttribute("openErrors", errors.countByResolvedFalse());
        model.addAttribute("openCritical", errors.countByResolvedFalseAndSeverity(ErrorSeverity.CRITICAL));
        model.addAttribute("openHigh", errors.countByResolvedFalseAndSeverity(ErrorSeverity.HIGH));
        model.addAttribute("errorsToday", errors.countByResolvedFalseAndLastSeenAfter(LocalDateTime.now().minusHours(24)));
        model.addAttribute("assistant", assistantMetrics());
        return "admin/monitoring";
    }

    private static List<Check> checks(HealthComponent h) {
        List<Check> checks = new ArrayList<>();
        if (h instanceof CompositeHealth c) {
            c.getComponents().forEach((name, part) -> checks.add(new Check(name, part.getStatus().getCode(),
                    part instanceof Health ph ? ph.getDetails() : Map.of())));
        }
        return checks;
    }

    /** "View more" on a health card: everything the check reported, in full. */
    @GetMapping("/health/{name}")
    public String healthDetail(@PathVariable String name, Model model) {
        Check check = checks(health.health()).stream().filter(c -> c.name().equals(name)).findFirst()
                .orElseThrow(() -> new BusinessException("There's no health check called " + name + "."));
        model.addAttribute("check", check);
        model.addAttribute("checkedAt", LocalDateTime.now());
        model.addAttribute("active", "monitoring");
        return "admin/monitoring-health";
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

    /** An admin's rating sticks; "AUTO" hands it back to monitoring. */
    @PostMapping("/errors/{id}/severity")
    @Transactional
    public String severity(@PathVariable Long id, @RequestParam String severity, RedirectAttributes ra) {
        AppError e = find(id);
        if ("AUTO".equals(severity)) {
            e.setSeverityPinned(false);
            e.setSeverity(ErrorMonitor.rate(e));
        } else {
            e.setSeverityPinned(true);
            e.setSeverity(Arrays.stream(ErrorSeverity.values()).filter(s -> s.name().equals(severity)).findFirst()
                    .orElseThrow(() -> new BusinessException("Unknown severity.")));
        }
        ra.addFlashAttribute("success", "Severity set to " + e.getSeverity().getLabel()
                + (e.isSeverityPinned() ? "." : " (automatic)."));
        return "redirect:/admin/monitoring" + (e.isResolved() ? "?errorsView=resolved" : "") + "#errors";
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
