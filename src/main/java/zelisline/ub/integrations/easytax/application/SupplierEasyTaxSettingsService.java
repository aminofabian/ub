package zelisline.ub.integrations.easytax.application;

import java.time.Instant;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxTestResponse;
import zelisline.ub.integrations.easytax.api.dto.SupplierEasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.SupplierEasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.domain.EasyTaxEnvironment;
import zelisline.ub.integrations.easytax.domain.SupplierEasyTaxSettings;
import zelisline.ub.integrations.easytax.infrastructure.EasyTaxClient;
import zelisline.ub.integrations.easytax.repository.SupplierEasyTaxSettingsRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

/**
 * Supplier-side EasyTax opt-in. Suppliers file under their own TIN with their own
 * EasyTax/VSCU, so these settings never touch a tenant's configuration; the endpoint
 * still comes from the platform.
 */
@Service
@RequiredArgsConstructor
public class SupplierEasyTaxSettingsService {

    private static final int MESSAGE_MAX = 500;

    private final SupplierEasyTaxSettingsRepository repository;
    private final PlatformEasyTaxSettingsService platformSettingsService;
    private final EasyTaxClient client;
    private final CredentialEncryptionService encryptionService;

    @Transactional(readOnly = true)
    public SupplierEasyTaxSettingsResponse get(String supplierId) {
        return toResponse(loadOrDisabled(supplierId));
    }

    @Transactional
    public SupplierEasyTaxSettingsResponse update(
            String supplierId,
            SupplierEasyTaxSettingsRequest request
    ) {
        SupplierEasyTaxSettings settings = loadOrDisabled(supplierId);

        if (request.environment() != null) {
            settings.setEnvironment(parseEnvironment(request.environment()));
        }
        if (request.username() != null) {
            settings.setUsername(trimToNull(request.username()));
        }
        if (request.tin() != null) {
            settings.setTin(trimToNull(request.tin()));
        }
        if (request.kraBranchId() != null) {
            settings.setKraBranchId(trimToNull(request.kraBranchId()));
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
                    "Add your TIN, EasyTax username, password, KRA branch ID, and device serial "
                            + "before enabling the integration");
        }
        if (settings.isEnabled() && !platformSettingsService.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "EasyTax is not enabled by the platform administrator");
        }

        repository.save(settings);
        return toResponse(settings);
    }

    @Transactional
    public EasyTaxTestResponse testConnection(String supplierId) {
        SupplierEasyTaxSettings settings = loadOrDisabled(supplierId);
        if (settings.getPasswordEncrypted() == null || settings.getPasswordEncrypted().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Add your EasyTax password before testing the connection");
        }

        String baseUrl = platformSettingsService.resolveBaseUrl(settings.getEnvironment())
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

    private SupplierEasyTaxSettings loadOrDisabled(String supplierId) {
        return repository.findById(supplierId)
                .orElseGet(() -> SupplierEasyTaxSettings.disabledFor(supplierId));
    }

    private SupplierEasyTaxSettingsResponse toResponse(SupplierEasyTaxSettings settings) {
        return new SupplierEasyTaxSettingsResponse(
                settings.isEnabled(),
                settings.getEnvironment() != null ? settings.getEnvironment().name() : null,
                settings.getUsername(),
                settings.getPasswordEncrypted() != null && !settings.getPasswordEncrypted().isBlank(),
                settings.getTin(),
                settings.getKraBranchId(),
                settings.getDeviceSerial(),
                settings.isConfigured(),
                platformSettingsService.isEnabled(),
                platformSettingsService.resolveBaseUrl(settings.getEnvironment()).orElse(null),
                settings.getLastTestedAt() != null ? settings.getLastTestedAt().toString() : null,
                settings.getLastTestOk(),
                settings.getLastTestMessage());
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
