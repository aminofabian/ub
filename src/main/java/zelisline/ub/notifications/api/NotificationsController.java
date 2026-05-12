package zelisline.ub.notifications.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
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
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationsController {

    private final NotificationService notificationService;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'reports.notifications.read')")
    public List<NotificationResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return notificationService.list(businessId).stream().map(NotificationsController::toDto).toList();
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("hasPermission(null, 'reports.notifications.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        notificationService.markRead(businessId, id);
        eventPublisher.publishEvent(
                new RealtimeBridge.NotificationReadEvent(businessId, principal.userId(), id));
    }

    private static NotificationResponse toDto(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getPayloadJson(), n.getReadAt(), n.getCreatedAt());
    }
}
