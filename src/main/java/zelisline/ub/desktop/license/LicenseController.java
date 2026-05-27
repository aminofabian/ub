package zelisline.ub.desktop.license;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.application.DesktopSetupService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * License status and renewal endpoints for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §10).
 *
 * <p>Both endpoints require authentication — they're available to any
 * logged‑in user so the UI can show the license banner, but only the owner
 * can submit a new license (the frontend enforces this; the backend just
 * verifies the token).
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/license")
@RequiredArgsConstructor
public class LicenseController {

    private final LicenseService licenseService;
    private final DesktopLicenseGuard licenseGuard;
    private final BusinessRepository businessRepository;
    private final DesktopSetupService desktopSetupService;

    /**
     * Current license state. The frontend calls this on every page load
     * to decide whether to show the trial banner / expiry warning / read‑only
     * overlay.
     */
    @GetMapping("/status")
    public LicenseStatus status() {
        String businessId = desktopSetupService.getDesktopBusinessId();
        if (businessId.isEmpty()) {
            return LicenseStatus.invalid("Business not configured. Run the first-run wizard.");
        }

        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            return LicenseStatus.trialActive(30);
        }

        return licenseGuard.currentStatus();
    }

    /**
     * Submit a new license key. The caller must be authenticated (the desktop
     * web security chain requires a valid JWT for /api/**). The token is
     * verified and, if valid, stored in the business settings.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public LicenseStatus renew(@Valid @RequestBody LicenseRenewRequest request) {
        String businessId = desktopSetupService.getDesktopBusinessId();
        if (businessId.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Business not configured");
        }

        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Business not found"));

        LicensePayload payload = licenseService.decodeAndVerify(request.token());
        if (payload == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid license key — signature verification failed. "
                            + "Make sure you pasted the entire token.");
        }

        // Store the new token in business settings
        String settings = business.getSettings();
        try {
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root =
                    (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(settings);
            com.fasterxml.jackson.databind.node.ObjectNode desktopNode =
                    root.withObject("/desktop");
            desktopNode.put("licenseKey", request.token().trim());
            business.setSettings(json.writeValueAsString(root));
            businessRepository.save(business);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to store license key: " + e.getMessage(), e);
        }

        return licenseService.checkStatus(request.token().trim(), business.getName());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private String readStoredLicenseToken(Business business) {
        String settings = business.getSettings();
        if (settings == null || settings.isBlank()) {
            return null;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper json =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = json.readTree(settings);
            com.fasterxml.jackson.databind.JsonNode licenseKey = root.path("desktop").path("licenseKey");
            if (licenseKey.isTextual() && !licenseKey.asText().isBlank()) {
                return licenseKey.asText();
            }
        } catch (Exception e) {
            // Corrupt settings — treat as no license
        }
        return null;
    }

    /** Request body for POST /api/v1/license. */
    public record LicenseRenewRequest(
            @NotBlank String token
    ) {
    }
}
