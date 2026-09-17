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

        return withCloudPlan(
            licenseService.checkStatus(
                readStoredLicenseToken(business),
                business.getName()
            ),
            business.getSettings()
        );
    }

    /**
     * The license plan/expiry mirrors the shop's cloud subscription, but the
     * values inside the signed token are frozen at issue time. When the shop
     * upgrades or renews on the cloud and the till pulls master data, the
     * current tier + period end are stamped into {@code settings.desktop} —
     * prefer them so the till never reports a stale plan. Offline shops with
     * no stamp keep the token's values.
     */
    private static LicenseStatus withCloudPlan(LicenseStatus status, String settings) {
        if (status == null || settings == null || settings.isBlank()) {
            return status;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper json =
                new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode desktop = json.readTree(settings).path("desktop");
            String tier = desktop.path("cloudPlanTier").asText(null);
            String expiresRaw = desktop.path("cloudPlanExpiresAt").asText(null);
            java.time.Instant cloudExpires = null;
            if (expiresRaw != null && !expiresRaw.isBlank()) {
                try {
                    cloudExpires = java.time.Instant.parse(expiresRaw.trim());
                } catch (Exception ignored) {
                    // Corrupt stamp — fall back to the token expiry.
                }
            }
            return status.withCloudSubscription(tier, cloudExpires);
        } catch (Exception ignored) {
            return status;
        }
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
