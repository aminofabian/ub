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
 * Owns the opt-in EasyTax (KRA eTIMS) configuration for a business: reading and
 * updating settings, testing connectivity, and resolving the active config for
 * the submission pipeline to consume.
 */
@Service
@RequiredArgsConstructor
public class EasyTaxSettingsService {

    private static final int MESSAGE_MAX = 500;

    private final EasyTaxSettingsRepository repository;
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
        if (request.baseUrl() != null) {
            settings.setBaseUrl(trimToNull(request.baseUrl()));
        }
        if (request.username() != null) {
            settings.setUsername(trimToNull(request.username()));
        }
        if (request.tin() != null) {
            settings.setTin(trimToNull(request.tin()));
        }
        if (request.branchId() != null) {
            settings.setBranchId(trimToNull(request.branchId()));
        }
        if (request.deviceSerial() != null) {
            settings.setDeviceSerial(trimToNull(request.deviceSerial()));
        }
        if (request.password() != null && !request.password().isBlank()) {
            settings.setPasswordEncrypted(encryptionService.encryptSecret(request.password()));
        }
        if (request.enabled() != null) {
            settings.setEnabled(request.enabled());
        }

        if (settings.isEnabled() && !settings.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add the EasyTax base URL, username, password, TIN, branch ID, and device serial "
                            + "before enabling the integration");
        }

        repository.save(settings);
        return toResponse(settings);
    }

    /** True when the shop has opted in. Cheap check for other services. */
    @Transactional(readOnly = true)
    public boolean isEnabled(String businessId) {
        return repository.findById(businessId)
                .filter(EasyTaxSettings::isEnabled)
                .isPresent();
    }

    /**
     * Resolved settings when the business has opted in and is fully configured.
     * The submission pipeline should treat an empty result as "do not call EasyTax".
     */
    @Transactional(readOnly = true)
    public Optional<EasyTaxSettings> resolveActive(String businessId) {
        return repository.findById(businessId)
                .filter(EasyTaxSettings::isEnabled)
                .filter(EasyTaxSettings::isConfigured);
    }

    @Transactional
    public EasyTaxTestResponse testConnection(String businessId) {
        EasyTaxSettings settings = loadOrDisabled(businessId);
        if (settings.getBaseUrl() == null || settings.getBaseUrl().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add the EasyTax base URL before testing the connection");
        }
        if (settings.getPasswordEncrypted() == null || settings.getPasswordEncrypted().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add the EasyTax password before testing the connection");
        }

        String password = encryptionService.decrypt(settings.getPasswordEncrypted());
        EasyTaxClient.ProbeResult result = client.probe(settings.getBaseUrl(), settings.getUsername(), password);

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

    private static EasyTaxSettingsResponse toResponse(EasyTaxSettings settings) {
        return new EasyTaxSettingsResponse(
                settings.isEnabled(),
                settings.getEnvironment() != null ? settings.getEnvironment().name() : null,
                settings.getBaseUrl(),
                settings.getUsername(),
                settings.getPasswordEncrypted() != null && !settings.getPasswordEncrypted().isBlank(),
                settings.getTin(),
                settings.getBranchId(),
                settings.getDeviceSerial(),
                settings.isConfigured(),
                settings.getLastTestedAt() != null ? settings.getLastTestedAt().toString() : null,
                settings.getLastTestOk(),
                settings.getLastTestMessage());
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
