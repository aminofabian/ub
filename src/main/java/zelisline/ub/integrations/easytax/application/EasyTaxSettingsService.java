package zelisline.ub.integrations.easytax.application;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxTestResponse;
import zelisline.ub.integrations.easytax.domain.EasyTaxEnvironment;
import zelisline.ub.integrations.easytax.domain.EasyTaxSettings;
import zelisline.ub.integrations.easytax.infrastructure.EasyTaxClient;
import zelisline.ub.integrations.easytax.repository.EasyTaxSettingsRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

/**
 * Owns the tenant's opt-in EasyTax configuration. The endpoint comes from
 * {@link PlatformEasyTaxSettingsService}; the tenant supplies only credentials and TIN,
 * and each branch supplies its KRA branch id + device serial.
 */
@Service
@RequiredArgsConstructor
public class EasyTaxSettingsService {

    private static final int MESSAGE_MAX = 500;

    private final EasyTaxSettingsRepository repository;
    private final PlatformEasyTaxSettingsService platformSettingsService;
    private final EasyTaxClient client;
    private final CredentialEncryptionService encryptionService;

    @Transactional(readOnly = true)
    public EasyTaxSettingsResponse getSettings(String businessId) {
        return toResponse(loadOrDisabled(businessId));
    }

    @Transactional
    public EasyTaxSettingsResponse updateSettings(String businessId, EasyTaxSettingsRequest request) {
        EasyTaxSettings settings = loadOrDisabled(businessId);

        if (request.environment() != null) {
            settings.setEnvironment(parseEnvironment(request.environment()));
        }
        if (request.username() != null) {
            settings.setUsername(trimToNull(request.username()));
        }
        if (request.tin() != null) {
            settings.setTin(trimToNull(request.tin()));
        }
        if (request.password() != null && !request.password().isBlank()) {
            settings.setPasswordEncrypted(encryptionService.encryptSecret(request.password()));
        }
        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }

        if (settings.isEnabled() && !settings.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add the EasyTax username, password, and TIN before enabling the integration");
        }
        if (settings.isEnabled() && !platformSettingsService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "EasyTax is not enabled by the platform administrator");
        }

        repository.save(settings);
        return toResponse(settings);
    }

    /** True when the tenant has opted in. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String businessId) {
        return repository.findById(businessId)
                .filter(EasyTaxSettings::isEnabled)
                .isPresent();
    }

    /**
     * Resolved settings when the tenant has opted in and is fully configured. The
     * submission pipeline should treat an empty result as "do not call EasyTax".
     */
    @Transactional(readOnly = true)
    public Optional<EasyTaxSettings> resolveActive(String businessId) {
        return repository.findById(businessId)
                .filter(EasyTaxSettings::isEnabled)
                .filter(EasyTaxSettings::isConfigured);
    }

    /** Endpoint for these settings, from the platform configuration. */
    @Transactional(readOnly = true)
    public Optional<String> resolveBaseUrl(EasyTaxSettings settings) {
        return platformSettingsService.resolveBaseUrl(settings.getEnvironment());
    }

    @Transactional
    public EasyTaxTestResponse testConnection(String businessId) {
        EasyTaxSettings settings = loadOrDisabled(businessId);
        if (settings.getPasswordEncrypted() == null || settings.getPasswordEncrypted().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add the EasyTax password before testing the connection");
        }

        String baseUrl = resolveBaseUrl(settings)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "EasyTax is not available for " + environmentLabel(settings.getEnvironment())
                                + ". Ask your platform administrator to enable it and set the endpoint."));

        String password = encryptionService.decrypt(settings.getPasswordEncrypted());
        EasyTaxClient.ProbeResult result = client.probe(baseUrl, settings.getUsername(), password);

        settings.setLastTestedAt(Instant.now());
        settings.setLastTestOk(result.ok());
        settings.setLastTestMessage(truncate(result.message()));
        repository.save(settings);

        return new EasyTaxTestResponse(
                result.ok(),
                result.message(),
                settings.getLastTestedAt().toString());
    }

    private EasyTaxSettings loadOrDisabled(String businessId) {
        return repository.findById(businessId)
                .orElseGet(() -> EasyTaxSettings.disabledFor(businessId));
    }

    private EasyTaxSettingsResponse toResponse(EasyTaxSettings settings) {
        EasyTaxSettingsResponse response = new EasyTaxSettingsResponse(
                settings.isEnabled(),
                settings.getEnvironment() != null ? settings.getEnvironment().name() : null,
                settings.getUsername(),
                settings.getPasswordEncrypted() != null && !settings.getPasswordEncrypted().isBlank(),
                settings.getTin(),
                settings.isConfigured(),
                platformSettingsService.isEnabled(),
                platformSettingsService.resolveBaseUrl(settings.getEnvironment()).orElse(null),
                settings.getLastTestedAt() != null ? settings.getLastTestedAt().toString() : null,
                settings.getLastTestOk(),
                settings.getLastTestMessage());
        return response;
    }

    private static String environmentLabel(EasyTaxEnvironment environment) {
        return environment == EasyTaxEnvironment.PRODUCTION ? "the production environment" : "the sandbox";
    }

    private static EasyTaxEnvironment parseEnvironment(String raw) {
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (EasyTaxEnvironment candidate : EasyTaxEnvironment.values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "environment must be SANDBOX or PRODUCTION");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > MESSAGE_MAX ? value.substring(0, MESSAGE_MAX) : value;
    }
}
