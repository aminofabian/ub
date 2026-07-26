package zelisline.ub.platform.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.api.dto.SupplierPortalSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateSupplierPortalSettingsRequest;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/platform/supplier-portal")
@RequiredArgsConstructor
public class SuperAdminSupplierPortalSettingsController {

    private final PlatformSupplierPortalSettingsService settingsService;

    @GetMapping
    public SupplierPortalSettingsResponse get() {
        return settingsService.getForSuperAdmin();
    }

    @PutMapping
    public SupplierPortalSettingsResponse update(@Valid @RequestBody UpdateSupplierPortalSettingsRequest body) {
        return settingsService.update(body);
    }
}
