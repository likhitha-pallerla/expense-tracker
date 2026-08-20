package com.expensetracker.api.notifications;

import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expensetracker.api.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public List<NotificationView> list(
            @RequestParam(name = "includeDismissed", defaultValue = "false") boolean includeDismissed) {
        return notifications.list(CurrentUser.id(), includeDismissed);
    }

    /** Just the badge, so the nav does not have to fetch the whole list. */
    @GetMapping("/count")
    public Map<String, Long> count() {
        return Map.of("unread", notifications.unreadCount(CurrentUser.id()));
    }

    @PostMapping("/read")
    public NotificationView markRead(@Valid @RequestBody NotificationRequest request) {
        return notifications.markRead(CurrentUser.id(), request.key());
    }

    @PostMapping("/read-all")
    public Map<String, Integer> markAllRead() {
        return Map.of("marked", notifications.markAllRead(CurrentUser.id()));
    }

    @PostMapping("/dismiss")
    public NotificationView dismiss(@Valid @RequestBody NotificationRequest request) {
        return notifications.dismiss(CurrentUser.id(), request.key());
    }

    @PostMapping("/restore")
    public NotificationView restore(@Valid @RequestBody NotificationRequest request) {
        return notifications.restore(CurrentUser.id(), request.key());
    }
}
