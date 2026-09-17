package zelisline.ub.desktop.api;

import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.desktop.api.dto.DesktopSetupRequest;
import zelisline.ub.desktop.api.dto.DesktopSetupResponse;
import zelisline.ub.desktop.api.dto.DesktopSetupStatusResponse;
import zelisline.ub.desktop.application.CloudSyncSession;
import zelisline.ub.desktop.application.DesktopSetupService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * First-run setup endpoints — desktop SKU only (see {@code DESKTOP_INSTALLATION.md} §9).
 *
 * <p>Endpoints are intentionally <em>unauthenticated</em>: the install may have
 * no users yet (or the merchant is resetting from the login screen). The
 * {@code DesktopWebConfig} security chain whitelists
 * {@code /api/v1/desktop/setup/**}.
 */
@RestController
@Profile("desktop")
@RequestMapping("/api/v1/desktop/setup")
@RequiredArgsConstructor
public class DesktopSetupController {

    private static final String PLATFORM_DOMAIN = "kiosk.ke";

    private final DesktopSetupService desktopSetupService;
    private final BusinessRepository businessRepository;
    private final CloudSyncSession cloudSyncSession;

    /**
     * Cheap probe for the frontend router. When setup is done, also returns
     * the local shop name / host and cloud origin so login can show which
     * account this till belongs to.
     */
    @GetMapping("/status")
    public DesktopSetupStatusResponse status() {
        String businessId = desktopSetupService.getDesktopBusinessId();
        boolean setupRequired = desktopSetupService.isSetupRequired();
        String shopName = null;
        String shopHost = null;
        String cloudOrigin = null;
        if (!setupRequired && businessId != null && !businessId.isBlank()) {
            Business business = businessRepository
                .findByIdAndDeletedAtIsNull(businessId)
                .orElse(null);
            if (business != null) {
                shopName = business.getName() == null ? null : business.getName().trim();
                if (shopName != null && shopName.isEmpty()) {
                    shopName = null;
                }
                String slug = business.getSlug() == null
                    ? null
                    : business.getSlug().trim().toLowerCase(Locale.ROOT);
                if (slug != null && !slug.isEmpty() && !"desktop".equals(slug)) {
                    shopHost = slug + "." + PLATFORM_DOMAIN;
                }
            }
            cloudOrigin = cloudSyncSession
                .load()
                .map(CloudSyncSession.Session::origin)
                .map(String::trim)
                .filter(o -> !o.isEmpty())
                .orElse(null);
        }
        return new DesktopSetupStatusResponse(
            setupRequired,
            businessId,
            shopName,
            shopHost,
            cloudOrigin
        );
    }

    @PostMapping
    public DesktopSetupResponse setup(@Valid @RequestBody DesktopSetupRequest request) {
        return desktopSetupService.completeSetup(request);
    }

    /**
     * Soft-delete the local shop and clear the init marker so the merchant can
     * pick a different shop from {@code /setup}. Called from the login screen
     * when they realise they connected the wrong account.
     */
    @PostMapping("/reset")
    public DesktopSetupStatusResponse reset() {
        if (desktopSetupService.isSetupRequired()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This till is not set up yet — open Setup to connect a shop"
            );
        }
        desktopSetupService.resetForSetup();
        return status();
    }
}
