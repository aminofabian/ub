package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.PlatformAuthSettingsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformAuthSettingsRequest;
import zelisline.ub.platform.application.PlatformAuthSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/auth")
@RequiredArgsConstructor
public class SuperAdminPlatformAuthSettingsController {

    private final PlatformAuthSettingsService platformAuthSettingsService;

    @GetMapping
    public PlatformAuthSettingsResponse get() {
        return platformAuthSettingsService.getForSuperAdmin();
    }

    @PutMapping
    public PlatformAuthSettingsResponse update(@Valid @RequestBody UpdatePlatformAuthSettingsRequest body) {
        return platformAuthSettingsService.update(body);
    }
}
