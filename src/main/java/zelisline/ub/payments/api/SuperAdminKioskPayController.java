package zelisline.ub.payments.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformKioskPaySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformKioskPaySettingsRequest;
import zelisline.ub.payments.application.PlatformKioskPaySettingsService;

@RestController
@RequestMapping("/api/v1/super-admin/payments/kiosk-pay")
@RequiredArgsConstructor
public class SuperAdminKioskPayController {

    private final PlatformKioskPaySettingsService settingsService;

    @GetMapping
    public PlatformKioskPaySettingsResponse get() {
        return settingsService.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformKioskPaySettingsResponse update(@Valid @RequestBody UpdatePlatformKioskPaySettingsRequest body) {
        return settingsService.update(body);
    }
}
