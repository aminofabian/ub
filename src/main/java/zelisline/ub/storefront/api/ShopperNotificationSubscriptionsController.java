package zelisline.ub.storefront.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.api.dto.NotificationSubscriptionResponse;
import zelisline.ub.notifications.application.NotificationSubscriptionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/me/shopper/notification-subscriptions")
@RequiredArgsConstructor
public class ShopperNotificationSubscriptionsController {

    private final NotificationSubscriptionService subscriptionService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<NotificationSubscriptionResponse> list(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return subscriptionService.listForUser(businessId, principal.userId());
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationSubscriptionResponse subscribe(
            @RequestBody Map<String, String> body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String itemId = body.getOrDefault("itemId", "").trim();
        String kind = body.getOrDefault("kind", "").trim();
        return subscriptionService.subscribe(businessId, principal.userId(), itemId, kind);
    }

    @DeleteMapping("/{itemId}/{kind}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(
            @PathVariable String itemId,
            @PathVariable String kind,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        subscriptionService.unsubscribe(businessId, principal.userId(), itemId.trim(), kind);
    }
}
