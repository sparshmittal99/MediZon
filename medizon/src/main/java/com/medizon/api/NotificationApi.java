package com.medizon.api;

import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.NotificationPush;
import com.medizon.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/** New alerts for the browser: a live push connection, plus a poll every open tab runs to fetch and show them. */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationApi {

    private final NotificationService notificationService;
    private final CurrentUser currentUser;
    private final NotificationPush push;

    /** Live connection: a "notify" event arrives the moment a notification is created. */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        return push.subscribe(currentUser.get().getId());
    }

    /** {@code after} is the newest notification id this browser has already shown. */
    @GetMapping("/poll")
    public Map<String, Object> poll(@RequestParam(defaultValue = "0") long after) {
        User user = currentUser.get();
        List<Map<String, Object>> fresh = notificationService.newSince(user, after).stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "type", n.getType().name(),
                        "title", n.getTitle(),
                        "message", n.getMessage() == null ? "" : n.getMessage(),
                        "url", "/notifications/" + n.getId() + "/open"))
                .toList();
        return Map.of("unread", notificationService.unreadCount(user), "items", fresh,
                "latest", notificationService.latestId(user));
    }
}
