package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.platform.api.dto.PlatformIntegrationsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.application.PlatformIntegrationSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/integrations")
@RequiredArgsConstructor
public class SuperAdminPlatformIntegrationsController {

    private final PlatformIntegrationSettingsService platformIntegrationSettingsService;

    @GetMapping
    public PlatformIntegrationsResponse get() {
        return platformIntegrationSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public PlatformIntegrationsResponse update(@Valid @RequestBody UpdatePlatformIntegrationsRequest body) {
        return platformIntegrationSettingsService.update(body);
    }
}
