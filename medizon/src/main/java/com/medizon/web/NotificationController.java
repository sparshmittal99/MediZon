package com.medizon.web;

import com.medizon.domain.Notification;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;

    /** Viewing the list counts as reading it: unread items are highlighted this once and the bell clears. */
    @GetMapping
    public String list(Model model) {
        User user = currentUser.get();
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
}
