package zelisline.ub.payments.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformDarajaSettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformDarajaSettingsRequest;
import zelisline.ub.payments.application.PlatformDarajaSettingsService;

/**
 * Super-admin Safaricom Daraja credentials (encrypted in DB — never env).
 */
@RestController
@RequestMapping("/api/v1/super-admin/payments/daraja")
@RequiredArgsConstructor
public class SuperAdminDarajaController {

    private final PlatformDarajaSettingsService settingsService;

    @GetMapping
    public PlatformDarajaSettingsResponse get() {
        return settingsService.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformDarajaSettingsResponse update(@Valid @RequestBody UpdatePlatformDarajaSettingsRequest body) {
        return settingsService.update(body);
    }

    @PostMapping("/test")
    public PlatformDarajaSettingsResponse test() {
        return settingsService.testConnection();
    }
}
