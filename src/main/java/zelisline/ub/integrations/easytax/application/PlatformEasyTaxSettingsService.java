package zelisline.ub.integrations.easytax.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.PlatformEasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.PlatformEasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.domain.EasyTaxEnvironment;
import zelisline.ub.integrations.easytax.domain.PlatformEasyTaxSettings;
import zelisline.ub.integrations.easytax.repository.PlatformEasyTaxSettingsRepository;

/**
 * Super-admin owned EasyTax configuration: whether tenants may use the integration at
 * all, and the EasyTax endpoint for each environment.
 */
@Service
@RequiredArgsConstructor
public class PlatformEasyTaxSettingsService {

    private final PlatformEasyTaxSettingsRepository repository;

    @Transactional(readOnly = true)
    public PlatformEasyTaxSettingsResponse get() {
        return toResponse(load());
    }

    @Transactional(readOnly = true)
    public boolean isEnabled() {
        return load().isEnabled();
    }

    /**
     * The EasyTax endpoint a tenant should talk to, or empty when the platform has not
     * enabled the integration (or has no URL for that environment).
     */
    @Transactional(readOnly = true)
    public Optional<String> resolveBaseUrl(EasyTaxEnvironment environment) {
        PlatformEasyTaxSettings settings = load();
        if (!settings.isEnabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(trimToNull(settings.baseUrlFor(environment)));
    }

    @Transactional
    public PlatformEasyTaxSettingsResponse update(PlatformEasyTaxSettingsRequest request) {
        PlatformEasyTaxSettings settings = load();
        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }
        if (request.sandboxBaseUrl() != null) {
            settings.setSandboxBaseUrl(trimToNull(request.sandboxBaseUrl()));
        }
        if (request.productionBaseUrl() != null) {
            settings.setProductionBaseUrl(trimToNull(request.productionBaseUrl()));
        }
        repository.save(settings);
        return toResponse(settings);
    }

    private PlatformEasyTaxSettings load() {
        return repository.findById(PlatformEasyTaxSettings.SINGLETON_ID)
                .orElseGet(() -> {
                    PlatformEasyTaxSettings fresh = new PlatformEasyTaxSettings();
                    fresh.setId(PlatformEasyTaxSettings.SINGLETON_ID);
                    return fresh;
                });
    }

    private static PlatformEasyTaxSettingsResponse toResponse(PlatformEasyTaxSettings settings) {
        return new PlatformEasyTaxSettingsResponse(
                settings.isEnabled(),
                settings.getSandboxBaseUrl(),
                settings.getProductionBaseUrl());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
