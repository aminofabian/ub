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
        return toResponse(resolveSingleton());
    }

    /** Tenant-facing readiness: whether till/paybill-only can be added right now. */
    @Transactional(readOnly = true)
    public zelisline.ub.payments.api.dto.MpesaCustodyAvailabilityResponse availabilityForTenant() {
        boolean available = custodyAvailableForTenants();
        return new zelisline.ub.payments.api.dto.MpesaCustodyAvailabilityResponse(
                available,
                activeProvider(),
                available ? null : notAvailableMessage());
    }

    @Transactional
    public PlatformMpesaCustodySettingsResponse update(UpdatePlatformMpesaCustodySettingsRequest body) {
        String provider;
        try {
            provider = PlatformMpesaCustodyProviders.normalize(body.custodyProvider());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }

        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(provider)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Till/paybill-only uses Daraja for now. Set the provider to Daraja or Off.");
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(provider)) {
            if (!isDarajaCollectReady()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Platform Daraja is not enabled/configured.");
            }
        }

        PlatformMpesaCustodySettings row = resolveSingletonForUpdate();
        row.setCustodyProvider(provider);
        return toResponse(repository.save(row));
    }

    @Transactional(readOnly = true)
    public String activeProvider() {
        return PlatformMpesaCustodyProviders.normalize(resolveSingleton().getCustodyProvider());
    }

    /** Tenant can activate CUSTODY_MPESA when SA picked a ready rail. */
    @Transactional(readOnly = true)
    public boolean custodyAvailableForTenants() {
        String p = activeProvider();
        if (PlatformMpesaCustodyProviders.OFF.equals(p)) {
            return false;
        }
        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(p)) {
            return false;
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(p)) {
            return isDarajaCollectReady();
        }
        return false;
    }

    @Transactional(readOnly = true)
    public String notAvailableMessage() {
        String p = activeProvider();
        if (PlatformMpesaCustodyProviders.OFF.equals(p)) {
            return "Kiosk-powered till/paybill is Off. Ask Super Admin to set Platform custody provider to Daraja.";
        }
        if (PlatformMpesaCustodyProviders.KOPOKOPO.equals(p)) {
            return "Till/paybill-only is Daraja-only right now. Ask Super Admin to set the provider to Daraja.";
        }
        if (PlatformMpesaCustodyProviders.DARAJA.equals(p)) {
            if (!isDarajaCollectReady()) {
                return "Platform Daraja is selected but is not enabled.";
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
     * Daraja B2B disburse is available once the platform Daraja account has the B2B
     * initiator credentials configured.
     */
    public boolean isDarajaDisburseAvailable() {
        PlatformDarajaSettingsService daraja = darajaSettingsService.getIfAvailable();
        return daraja != null && daraja.isB2bConfigured();
    }

    /** Read-only: falls back to a transient default (seeded by migration) without writing. */
    private PlatformMpesaCustodySettings resolveSingleton() {
        return repository.findById(PlatformMpesaCustodySettings.SINGLETON_ID)
                .orElseGet(() -> {
                    PlatformMpesaCustodySettings row = new PlatformMpesaCustodySettings();
                    row.setId(PlatformMpesaCustodySettings.SINGLETON_ID);
                    row.setCustodyProvider(PlatformMpesaCustodyProviders.OFF);
                    row.setUpdatedAt(Instant.now());
                    return row;
                });
    }

    /** Write path: creates the singleton row if it is missing. */
    private PlatformMpesaCustodySettings resolveSingletonForUpdate() {
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
