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

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/domains")
@RequiredArgsConstructor
public class SuperAdminPlatformDomainSettingsController {

    private final PlatformDomainSettingsService platformDomainSettingsService;

    @GetMapping
    public PlatformDomainSettingsResponse get() {
        return platformDomainSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public PlatformDomainSettingsResponse update(@Valid @RequestBody UpdatePlatformDomainSettingsRequest body) {
        return platformDomainSettingsService.update(body);
    }
}
