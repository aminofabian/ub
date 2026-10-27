package zelisline.ub.notifications.api;

import java.util.List;

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
import zelisline.ub.notifications.api.dto.NotificationBellRow;
import zelisline.ub.notifications.api.dto.NotificationResponse;
import zelisline.ub.notifications.application.NotificationPresenter;
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

    /** How many rows the bell keeps — the drawer renders 50 and drops the rest. */
    private static final int BELL_ROWS = 50;

    private final NotificationService notificationService;
    private final NotificationPresenter notificationPresenter;
    private final ApplicationEventPublisher eventPublisher;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'reports.notifications.read')")
    public List<NotificationResponse> list(
            HttpServletRequest request,
            @RequestParam(name = "limit", required = false) Integer limit) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<Notification> rows = CurrentTenantUser.optionalHuman(request)
                .map(tp -> notificationService.listStaffInbox(businessId, tp.userId()))
                .orElseGet(() -> notificationService.list(businessId));
        // The bell only reads the newest slice; don't stream an unbounded inbox
        // full of payloadJson just to fill a badge.
        if (limit != null) {
            int cap = Math.min(Math.max(limit, 1), 500);
            rows = rows.stream().limit(cap).toList();
        }
        return rows.stream().map(NotificationsController::toDto).toList();
    }

    /**
     * Bell list: title, body, destination and read state, without payload JSON.
     *
     * <p>Split from {@link #list} on purpose. The bell is hydrated on every shell
     * mount and by the websocket REST fallback, so it has to stay small; the full
     * endpoint keeps returning payloads for callers that render a row's details.
     * Copy comes from {@link NotificationPresenter}, the same resolver the
     * websocket frame uses, so live and hydrated rows read identically.
     */
    @GetMapping("/bell")
    @PreAuthorize("hasPermission(null, 'reports.notifications.read')")
    public List<NotificationBellRow> bell(
            HttpServletRequest request,
            @RequestParam(name = "limit", required = false) Integer limit) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<Notification> rows = CurrentTenantUser.optionalHuman(request)
                .map(tp -> notificationService.listStaffInbox(businessId, tp.userId()))
                .orElseGet(() -> notificationService.list(businessId));
        int cap = limit == null ? BELL_ROWS : Math.min(Math.max(limit, 1), BELL_ROWS);
        return rows.stream()
                .limit(cap)
                .map(this::toBellRow)
                .toList();
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

    /** Clears the staff bell: business-wide rows plus this user's own. */
    @PostMapping("/read-all")
    @PreAuthorize("hasPermission(null, 'reports.notifications.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        notificationService.markAllReadForStaffInbox(businessId, principal.userId());
    }

    private NotificationBellRow toBellRow(Notification n) {
        NotificationPresenter.Presentation copy =
                notificationPresenter.present(n.getType(), n.getPayloadJson());
        return new NotificationBellRow(
                n.getId(),
                n.getType(),
                n.getPriority(),
                copy.title(),
                copy.body(),
                copy.actionUrl(),
                n.getReadAt(),
                n.getCreatedAt());
    }

    private static NotificationResponse toDto(Notification n) {
        return new NotificationResponse(n.getId(), n.getType(), n.getPayloadJson(), n.getReadAt(), n.getCreatedAt());
    }
}
