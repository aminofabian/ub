package zelisline.ub.tenancy.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.dto.OnboardingPatchRequest;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.api.dto.StoreSectionStarterKitResponse;
import zelisline.ub.tenancy.application.StoreSectionStarterKitCatalog;
import zelisline.ub.tenancy.application.TenancyService;

@Validated
@RestController
@RequestMapping("/api/v1/businesses/me/onboarding")
@RequiredArgsConstructor
public class MyOnboardingController {

    private static final String MANAGE_SETTINGS =
            "hasPermission(null, 'business.manage_settings')";

    private final TenancyService tenancyService;

    @GetMapping
    public OnboardingSettingsResponse getOnboarding(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tenancyService.getOnboardingForTenant(
                TenantRequestIds.resolveBusinessId(request)
        );
    }

    @PatchMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public OnboardingSettingsResponse patchOnboarding(
            HttpServletRequest request,
            @Valid @RequestBody OnboardingPatchRequest patch
    ) {
        CurrentTenantUser.require(request);
        return tenancyService.updateOnboardingForTenant(
                TenantRequestIds.resolveBusinessId(request),
                patch
        );
    }

    @GetMapping("/store-section-kits")
    public List<StoreSectionStarterKitResponse> storeSectionKits(
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return StoreSectionStarterKitCatalog.KITS;
    }
}
