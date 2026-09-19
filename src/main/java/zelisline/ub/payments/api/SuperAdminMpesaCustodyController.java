package zelisline.ub.payments.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformMpesaCustodySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformMpesaCustodySettingsRequest;
import zelisline.ub.payments.application.PlatformMpesaCustodySettingsService;

@RestController
@RequestMapping("/api/v1/super-admin/payments/mpesa-custody")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminMpesaCustodyController {

    private final PlatformMpesaCustodySettingsService service;

    @GetMapping
    public PlatformMpesaCustodySettingsResponse get() {
        return service.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformMpesaCustodySettingsResponse update(
            @Valid @RequestBody UpdatePlatformMpesaCustodySettingsRequest body) {
        return service.update(body);
    }
}
