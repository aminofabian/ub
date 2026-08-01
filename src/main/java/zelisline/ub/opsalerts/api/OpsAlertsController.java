package zelisline.ub.opsalerts.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.opsalerts.api.dto.OpsAlertSettingsResponse;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendRequest;
import zelisline.ub.opsalerts.api.dto.OpsAlertTestSendResponse;
import zelisline.ub.opsalerts.api.dto.SendOpsAlertPhoneVerificationRequest;
import zelisline.ub.opsalerts.api.dto.SendOpsAlertPhoneVerificationResponse;
import zelisline.ub.opsalerts.api.dto.UpdateOpsAlertSettingsRequest;
import zelisline.ub.opsalerts.api.dto.VerifyOpsAlertPhoneVerificationRequest;
import zelisline.ub.opsalerts.api.dto.VerifyOpsAlertPhoneVerificationResponse;
import zelisline.ub.opsalerts.application.BusinessOpsAlertSettingsService;
import zelisline.ub.opsalerts.application.OpsAlertPhoneVerificationService;
import zelisline.ub.opsalerts.application.TenantOpsAlertDispatcher;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/ops-alerts")
@RequiredArgsConstructor
public class OpsAlertsController {

    private final BusinessOpsAlertSettingsService settingsService;
    private final OpsAlertPhoneVerificationService phoneVerificationService;
    private final TenantOpsAlertDispatcher dispatcher;

    @GetMapping("/settings")
    @PreAuthorize("hasPermission(null, 'credits.settings.write') or hasPermission(null, 'credits.customers.read')")
    public OpsAlertSettingsResponse getSettings(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return settingsService.getForAdmin(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping("/settings")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public OpsAlertSettingsResponse updateSettings(
            @Valid @RequestBody UpdateOpsAlertSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return settingsService.update(TenantRequestIds.resolveBusinessId(request), body);
    }

    @DeleteMapping("/settings/phone")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public OpsAlertSettingsResponse clearPhone(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return settingsService.clearPhone(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/phone-verifications")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public SendOpsAlertPhoneVerificationResponse sendVerification(
            @Valid @RequestBody SendOpsAlertPhoneVerificationRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return phoneVerificationService.send(TenantRequestIds.resolveBusinessId(request), body.phone());
    }

    @PostMapping("/phone-verifications/verify")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public VerifyOpsAlertPhoneVerificationResponse verify(
            @Valid @RequestBody VerifyOpsAlertPhoneVerificationRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return phoneVerificationService.verify(
                TenantRequestIds.resolveBusinessId(request), body.phone(), body.code());
    }

    @PostMapping("/test")
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public OpsAlertTestSendResponse test(
            @Valid @RequestBody OpsAlertTestSendRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return dispatcher.sendTest(
                TenantRequestIds.resolveBusinessId(request),
                body.phone(),
                body.message());
    }
}
