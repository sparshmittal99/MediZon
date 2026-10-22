package com.medizon.web;

import com.medizon.domain.Notification;
import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeviceService;
import com.medizon.service.MemberService;
import com.medizon.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final DeviceService deviceService;
    private final MemberService memberService;
    private final CurrentUser currentUser;

    /** Viewing the list counts as reading it: unread items are highlighted this once and the bell clears. */
    @GetMapping
    public String list(Model model) {
        User user = currentUser.get();
        notificationService.trim(user);   // older notifications from before customers kept only 20
        List<Notification> list = notificationService.recent(user);
        model.addAttribute("notifications", list);
        model.addAttribute("fresh", list.stream().filter(n -> !n.isRead()).map(Notification::getId).collect(Collectors.toSet()));
        notificationService.markAllRead(user);
        model.addAttribute("unreadCount", 0L);
        return "notifications";
    }

    @GetMapping("/{id}/open")
    public String open(@PathVariable Long id) {
        String link = notificationService.open(id, currentUser.get());
        return "redirect:" + (link.startsWith("/") ? link : "/notifications");
    }

    @PostMapping("/read-all")
    public String readAll() {
        notificationService.markAllRead(currentUser.get());
        return "redirect:/notifications";
    }

    // ---------------------------------------------------------------- per-device settings (customers)

    @GetMapping("/settings")
    public String settings(Model model, HttpServletRequest request, HttpServletResponse response) {
        User user = currentUser.get();
        if (user.isStaff()) return "redirect:/notifications";
        NotificationDevice here = deviceService.current(user, request, response);
        model.addAttribute("here", here);
        model.addAttribute("others", deviceService.forUser(user).stream().filter(d -> !d.getId().equals(here.getId())).toList());
        model.addAttribute("members", memberService.all(user));
        return "notification-settings";
    }

    @PostMapping("/settings")
    public String saveSettings(@RequestParam Long deviceId, @RequestParam(required = false) String label,
                               @RequestParam(defaultValue = "true") boolean allMembers,
                               @RequestParam(required = false) List<Long> memberIds,
                               @RequestParam(required = false) String returnTo, RedirectAttributes ra) {
        NotificationDevice d = deviceService.save(currentUser.get(), deviceId, label, allMembers, memberIds);
        ra.addFlashAttribute("success", "This device will pop up alerts for: " + d.getSummary() + ".");
        return "redirect:" + (returnTo != null && returnTo.startsWith("/") && !returnTo.startsWith("//") ? returnTo : "/notifications/settings");
    }

    @PostMapping("/devices/{id}/forget")
    public String forget(@PathVariable Long id, RedirectAttributes ra) {
        deviceService.forget(id, currentUser.get());
        ra.addFlashAttribute("success", "Device removed. It gets everyone's alerts again if you sign in there.");
        return "redirect:/notifications/settings";
    }
}
