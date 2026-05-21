package zelisline.ub.notifications.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import zelisline.ub.notifications.api.dto.PushConfigResponse;
import zelisline.ub.notifications.api.dto.RegisterDeviceTokenRequest;
import zelisline.ub.notifications.api.dto.RegisterFcmTokenRequest;
import zelisline.ub.notifications.application.DeviceTokenService;
import zelisline.ub.notifications.config.FcmProperties;
import zelisline.ub.notifications.config.WebPushProperties;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
@Validated
public class DeviceTokensController {

    private final DeviceTokenService deviceTokenService;
    private final WebPushProperties webPushProperties;
    private final FcmProperties fcmProperties;

    @GetMapping("/push/config")
    @PreAuthorize("isAuthenticated()")
    public PushConfigResponse pushConfig() {
        return new PushConfigResponse(
                webPushProperties.configured(),
                webPushProperties.configured() ? webPushProperties.vapidPublicKey() : null,
                fcmProperties.configured());
    }

    @PostMapping("/device-tokens")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void register(@Valid @RequestBody RegisterDeviceTokenRequest body, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String userAgent = request.getHeader("User-Agent");
        deviceTokenService.register(businessId, principal.userId(), body, userAgent);
    }

    @PostMapping("/device-tokens/fcm")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void registerFcm(@Valid @RequestBody RegisterFcmTokenRequest body, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String userAgent = request.getHeader("User-Agent");
        deviceTokenService.registerFcm(businessId, principal.userId(), body, userAgent);
    }

    @DeleteMapping("/device-tokens/{id}")
    @PreAuthorize("isAuthenticated()")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable String id, HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        deviceTokenService.revoke(businessId, principal.userId(), id.trim());
    }
}
