package zelisline.ub.notifications.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.notifications.api.dto.CreateNotificationCampaignRequest;
import zelisline.ub.notifications.api.dto.NotificationCampaignResponse;
import zelisline.ub.notifications.application.NotificationCampaignService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/notification-campaigns")
@RequiredArgsConstructor
@Validated
public class NotificationCampaignsController {

    private final NotificationCampaignService campaignService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'notifications.promotions.manage')")
    public List<NotificationCampaignResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return campaignService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'notifications.promotions.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public NotificationCampaignResponse create(
            @Valid @RequestBody CreateNotificationCampaignRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return campaignService.create(
                TenantRequestIds.resolveBusinessId(request),
                principal.userId(),
                body);
    }

    @PostMapping("/{campaignId}/run")
    @PreAuthorize("hasPermission(null, 'notifications.promotions.manage')")
    public NotificationCampaignResponse runNow(
            @PathVariable String campaignId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return campaignService.runNow(
                TenantRequestIds.resolveBusinessId(request),
                campaignId.trim());
    }

    @PostMapping("/{campaignId}/cancel")
    @PreAuthorize("hasPermission(null, 'notifications.promotions.manage')")
    public NotificationCampaignResponse cancel(
            @PathVariable String campaignId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return campaignService.cancel(
                TenantRequestIds.resolveBusinessId(request),
                campaignId.trim());
    }
}
