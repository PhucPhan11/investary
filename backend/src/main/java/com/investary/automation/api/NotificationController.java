package com.investary.automation.api;

import java.util.Map;
import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.investary.automation.application.notification.NotificationService;
import com.investary.automation.application.notification.NotificationService.NotificationView;
import com.investary.automation.domain.NotificationType;
import com.investary.common.web.PageResponse;

/**
 * In-app notifications (Phase 5 / Milestone 4). Local-only ({@link LocalOnlyRequestFilter}).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public PageResponse<NotificationView> list(@RequestParam(defaultValue = "false") boolean unreadOnly,
                                               @RequestParam(required = false) NotificationType type,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return notifications.list(unreadOnly, type, Math.max(0, page), Math.clamp(size, 1, MAX_PAGE_SIZE));
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unreadCount() {
        return Map.of("count", notifications.unreadCount());
    }

    @PatchMapping("/{id}/read")
    public NotificationView read(@PathVariable UUID id) {
        return notifications.markRead(id, true);
    }

    @PatchMapping("/{id}/unread")
    public NotificationView unread(@PathVariable UUID id) {
        return notifications.markRead(id, false);
    }

    @PostMapping("/read-all")
    public Map<String, Integer> readAll() {
        return Map.of("updated", notifications.markAllRead());
    }
}
