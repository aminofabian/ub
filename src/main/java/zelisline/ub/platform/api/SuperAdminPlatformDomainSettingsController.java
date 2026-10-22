package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.api.dto.PlatformDomainSettingsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformDomainSettingsRequest;
import zelisline.ub.platform.application.PlatformDomainSettingsService;
import zelisline.ub.tenancy.integrations.coolify.CoolifyDiagnosticsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/domains")
@RequiredArgsConstructor
public class SuperAdminPlatformDomainSettingsController {

    private final PlatformDomainSettingsService platformDomainSettingsService;
    private final CoolifyDiagnosticsService coolifyDiagnosticsService;

    @GetMapping
    public PlatformDomainSettingsResponse get() {
        return platformDomainSettingsService.getForSuperAdmin();
    }

    /**
     * "Test connection" for the custom-domain host. Read-only: probes
     * {@code /version} and {@code /applications/{uuid}} and explains a 401/403
     * instead of showing a bare status code.
     */
    @GetMapping("/coolify/diagnose")
    public CoolifyDiagnosticsService.CoolifyDiagnostic diagnoseCoolify() {
        return coolifyDiagnosticsService.diagnose();
    }

    @PutMapping
    public PlatformDomainSettingsResponse update(@Valid @RequestBody UpdatePlatformDomainSettingsRequest body) {
        return platformDomainSettingsService.update(body);
    }
}
