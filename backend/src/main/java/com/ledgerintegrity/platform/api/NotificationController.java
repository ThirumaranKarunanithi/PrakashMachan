package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.auth.CurrentUser;
import com.ledgerintegrity.platform.notify.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** §18.3: in-app notification centre for the current firm. */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService service;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    public record NotificationDto(String type, String message, Instant createdAt, boolean read) {}

    @GetMapping
    public Map<String, Object> list() {
        var firmId = currentUser.firmId();
        List<NotificationDto> items = service.list(firmId).stream()
                .map(n -> new NotificationDto(n.getType(), n.getMessage(), n.getCreatedAt(), n.getReadAt() != null))
                .toList();
        return Map.of("unread", service.unreadCount(firmId), "items", items);
    }

    @PostMapping("/read-all")
    public Map<String, String> readAll() {
        service.markAllRead(currentUser.firmId());
        return Map.of("status", "ok");
    }
}
