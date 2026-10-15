package com.medizon.api;

import com.medizon.domain.NotificationDevice;
import com.medizon.domain.User;
import com.medizon.service.CurrentUser;
import com.medizon.service.DeviceService;
import jakarta.servlet.http.HttpServletRequest;
import com.medizon.service.NotificationPush;
import com.medizon.service.NotificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.Principal;
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
    private final JdbcTemplate jdbc;
    private final DeviceService deviceService;

    /**
     * Live connection: a "notify" event arrives the moment a notification is created.
     * The id is read with plain JDBC so the connection goes straight back to the pool; a JPA lookup would
     * keep it pinned (open-in-view) for as long as this request stays open.
     */
    @GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(Principal principal) {
        Long userId = jdbc.queryForObject("select id from users where lower(email) = lower(?)", Long.class, principal.getName());
        return push.subscribe(userId);
    }

    /** {@code after} is the newest notification id this browser has already shown. */
    @GetMapping("/poll")
    public Map<String, Object> poll(@RequestParam(defaultValue = "0") long after, HttpServletRequest request) {
        User user = currentUser.get();
        NotificationDevice device = user.isStaff() ? null : deviceService.find(user, request);
        List<Map<String, Object>> fresh = notificationService.newSince(user, after, device).stream()
                .map(n -> Map.<String, Object>of(
                        "id", n.getId(),
                        "type", n.getType().name(),
                        "title", n.getTitle(),
                        "message", n.getMessage() == null ? "" : n.getMessage(),
                        "url", "/notifications/" + n.getId() + "/open"))
                .toList();
        return Map.of("unread", notificationService.unreadCount(user, device), "items", fresh,
                "latest", notificationService.latestId(user));
    }
}
