package zelisline.ub.marketplace.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalNotificationPrefsRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalNotificationPrefsResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalNotificationRow;
import zelisline.ub.marketplace.application.SupplierPortalNotificationsService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/notifications")
@RequiredArgsConstructor
public class SupplierPortalNotificationsController {

    private final SupplierPortalNotificationsService notificationsService;

    @GetMapping
    @PreAuthorize("hasRole('SUPPLIER')")
    public List<SupplierPortalNotificationRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return notificationsService.list(principal.marketplaceSupplierId());
    }

    @GetMapping("/unread-count")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Long> unreadCount() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return Map.of("count", notificationsService.unreadCount(principal.marketplaceSupplierId()));
    }

    @PostMapping("/{notificationId}/read")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> markRead(@PathVariable String notificationId) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        notificationsService.markRead(principal.marketplaceSupplierId(), notificationId);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    @PreAuthorize("hasRole('SUPPLIER')")
    public Map<String, Object> markAllRead() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        notificationsService.markAllRead(principal.marketplaceSupplierId());
        return Map.of("ok", true);
    }

    @GetMapping("/prefs")
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalNotificationPrefsResponse getPrefs() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return notificationsService.getPrefs(principal.userId(), principal.marketplaceSupplierId());
    }

    @PatchMapping("/prefs")
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalNotificationPrefsResponse patchPrefs(
            @Valid @RequestBody PatchSupplierPortalNotificationPrefsRequest body
    ) {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return notificationsService.patchPrefs(
                principal.userId(), principal.marketplaceSupplierId(), body);
    }
}
