package zelisline.ub.storefront.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.context.ApplicationEventPublisher;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.api.dto.NotificationResponse;
import zelisline.ub.notifications.application.NotificationService;
import zelisline.ub.notifications.domain.Notification;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/me/shopper/notifications")
@RequiredArgsConstructor
public class ShopperNotificationsController {

    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationResponse> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "40") int limit
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return notificationService.listForUser(businessId, principal.userId(), limit).stream()
                .map(ShopperNotificationsController::toDto)
                .toList();
    }

    @GetMapping("/unread-count")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Long> unreadCount(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        long count = notificationService.unreadCountForUser(businessId, principal.userId());
        return Map.of("unreadCount", count);
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        notificationService.markReadForUser(businessId, principal.userId(), id);
        eventPublisher.publishEvent(
                new RealtimeBridge.NotificationReadEvent(businessId, principal.userId(), id));
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        notificationService.markAllReadForUser(businessId, principal.userId());
    }

    private static NotificationResponse toDto(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getPayloadJson(), n.getReadAt(), n.getCreatedAt());
    }
}
