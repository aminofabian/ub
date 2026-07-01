package zelisline.ub.tenancy.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.PublicMobileConfigResponse;
import zelisline.ub.storefront.application.PublicMobileConfigService;
import zelisline.ub.tenancy.api.dto.MobileSettingsResponse;
import zelisline.ub.tenancy.api.dto.MobileTenantProfileExport;
import zelisline.ub.tenancy.api.dto.MyMobileConfigResponse;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class MyMobileConfigService {

    private final BusinessRepository businessRepository;
    private final BusinessMobileSettingsService businessMobileSettingsService;
    private final PublicMobileConfigService publicMobileConfigService;
    private final StorefrontSettingsService storefrontSettingsService;

    @Transactional
    public MyMobileConfigResponse getOrProvisionForBusiness(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        boolean wasProvisioned = businessMobileSettingsService
                .readFromSettingsJson(business.getSettings())
                .provisioned();
        String updatedSettings = businessMobileSettingsService.provisionIfMissing(business);
        boolean newlyProvisioned = !wasProvisioned
                && businessMobileSettingsService.readFromSettingsJson(updatedSettings).provisioned();
        if (!updatedSettings.equals(business.getSettings())) {
            business.setSettings(updatedSettings);
            business = businessRepository.save(business);
        }

        PublicMobileConfigResponse config = publicMobileConfigService.getForSlug(business.getSlug());
        MobileSettingsResponse mobile = businessMobileSettingsService.readFromSettingsJson(business.getSettings());
        TenantConfigBundle tenantConfig = storefrontSettingsService.readTenantConfig(
                business.getSettings(),
                business.getName());
        TenantBrandingDto branding = tenantConfig.branding();
        String displayName = branding.displayName() != null && !branding.displayName().isBlank()
                ? branding.displayName().trim()
                : business.getName();
        MobileTenantProfileExport profile = businessMobileSettingsService.buildTenantProfileExport(
                business,
                displayName,
                branding,
                mobile);

        return new MyMobileConfigResponse(config, profile, newlyProvisioned);
    }
}
