package zelisline.ub.desktop.license;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.application.DesktopSetupService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Resolves the current desktop license state for filters and controllers.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopLicenseGuard {

    private final LicenseService licenseService;
    private final DesktopSetupService desktopSetupService;
    private final BusinessRepository businessRepository;

    public LicenseStatus currentStatus() {
        String businessId = desktopSetupService.getDesktopBusinessId();
        if (businessId.isEmpty()) {
            return LicenseStatus.trialActive(30);
        }

        Business business = businessRepository
            .findByIdAndDeletedAtIsNull(businessId)
            .orElse(null);
        if (business == null) {
            return LicenseStatus.trialActive(30);
        }

        return licenseService.checkStatus(
            readStoredLicenseToken(business),
            business.getName()
        );
    }

    public boolean isReadOnly() {
        return currentStatus().readOnly();
    }

    private static String readStoredLicenseToken(Business business) {
        String settings = business.getSettings();
        if (settings == null || settings.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(settings);
            com.fasterxml.jackson.databind.JsonNode licenseKey = root
                .path("desktop")
                .path("licenseKey");
            if (licenseKey.isTextual() && !licenseKey.asText().isBlank()) {
                return licenseKey.asText();
            }
        } catch (Exception ignored) {
            // Corrupt settings — treat as no license
        }
        return null;
    }
}
