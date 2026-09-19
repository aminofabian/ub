package zelisline.ub.payments.application;

import java.time.Instant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformMpesaCustodySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformMpesaCustodySettingsRequest;
import zelisline.ub.payments.domain.PlatformMpesaCustodyProviders;
import zelisline.ub.payments.domain.PlatformMpesaCustodySettings;
import zelisline.ub.payments.repository.PlatformMpesaCustodySettingsRepository;

/**
 * Super Admin singleton: which platform rail powers tenant till/paybill-only (Model B).
 * Collect and settle always use this same provider.
 */
@Service
@RequiredArgsConstructor
public class PlatformMpesaCustodySettingsService {

    private final PlatformMpesaCustodySettingsRepository repository;
    private final ObjectProvider<PlatformKioskPaySettingsService> kioskPaySettingsService;
    private final ObjectProvider<PlatformDarajaSettingsService> darajaSettingsService;

    @Transactional(readOnly = true)
    public PlatformMpesaCustodySettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public PlatformMpesaCustodySettingsResponse update(UpdatePlatformMpesaCustodySettingsRequest body) {
        String provider;
        try {
            provider = PlatformMpesaCustodyProviders.normalize(body.custodyProvider());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(provider) && !isKopokopoReady()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Platform KopoKopo is not ready (need Kiosk Pay KopoKopo credentials for STK and Send Money).");
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(provider)) {
            if (!isDarajaCollectReady()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform Daraja is not enabled/configured.");
            }
            if (!isDarajaDisburseAvailable()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform Daraja custody is not available yet (disburse/B2C not implemented). Use KopoKopo for till/paybill-only, or leave Off.");
            }
        }

        PlatformMpesaCustodySettings row = loadSingleton();
        row.setCustodyProvider(provider);
        return toResponse(repository.save(row));
    }

    @Transactional(readOnly = true)
    public String activeProvider() {
        return PlatformMpesaCustodyProviders.normalize(loadSingleton().getCustodyProvider());
    }

    /** Tenant can activate CUSTODY_MPESA when SA picked a ready rail. */
    @Transactional(readOnly = true)
    public boolean custodyAvailableForTenants() {
        String p = activeProvider();
        if (PlatformMpesaCustodyProviders.OFF.equals(p)) {
            return false;
        }
        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(p)) {
            return isKopokopoReady();
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(p)) {
            return isDarajaCollectReady() && isDarajaDisburseAvailable();
        }
        return false;
    }

    @Transactional(readOnly = true)
    public String notAvailableMessage() {
        String p = activeProvider();
        if (PlatformMpesaCustodyProviders.OFF.equals(p)) {
            return "Kiosk-powered till/paybill is Off. Ask Super Admin to set Platform custody provider to KopoKopo or Daraja.";
        }
        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(p) && !isKopokopoReady()) {
            return "Platform KopoKopo is selected for custody but credentials are missing.";
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(p)) {
            if (!isDarajaCollectReady()) {
                return "Platform Daraja is selected for custody but is not enabled.";
            }
            if (!isDarajaDisburseAvailable()) {
                return "Platform Daraja custody is not available yet (disburse not implemented).";
            }
        }
        return "Kiosk-powered till/paybill is not available.";
    }

    public boolean isKopokopoReady() {
        PlatformKioskPaySettingsService kiosk = kioskPaySettingsService.getIfAvailable();
        return kiosk != null && kiosk.kopokopoCredentials().filter(c -> !c.isEmpty()).isPresent();
    }

    public boolean isDarajaCollectReady() {
        PlatformDarajaSettingsService daraja = darajaSettingsService.getIfAvailable();
        return daraja != null && daraja.isEnabledAndConfigured();
    }

    /**
     * Daraja B2C/B2B disburse is not shipped — custody provider DARAJA stays gated.
     */
    public boolean isDarajaDisburseAvailable() {
        return false;
    }

    private PlatformMpesaCustodySettings loadSingleton() {
        return repository.findById(PlatformMpesaCustodySettings.SINGLETON_ID)
                .orElseGet(() -> {
                    PlatformMpesaCustodySettings row = new PlatformMpesaCustodySettings();
                    row.setId(PlatformMpesaCustodySettings.SINGLETON_ID);
                    row.setCustodyProvider(PlatformMpesaCustodyProviders.OFF);
                    row.setUpdatedAt(Instant.now());
                    return repository.save(row);
                });
    }

    private PlatformMpesaCustodySettingsResponse toResponse(PlatformMpesaCustodySettings row) {
        return new PlatformMpesaCustodySettingsResponse(
                PlatformMpesaCustodyProviders.normalize(row.getCustodyProvider()),
                isKopokopoReady(),
                isDarajaCollectReady(),
                isDarajaDisburseAvailable(),
                row.getUpdatedAt());
    }
}
