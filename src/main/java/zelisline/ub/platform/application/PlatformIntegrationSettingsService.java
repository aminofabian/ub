package zelisline.ub.platform.application;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import zelisline.ub.catalog.config.CatalogAiProperties;
import zelisline.ub.messaging.config.MessagingProperties;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.PlatformIntegrationsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformIntegrationsRequest;
import zelisline.ub.platform.domain.PlatformIntegrationSettings;
import zelisline.ub.platform.repository.PlatformIntegrationSettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformIntegrationSettingsService {

    private final PlatformIntegrationSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final CatalogAiProperties catalogAiProperties;
    private final MessagingProperties messagingProperties;

    @Transactional(readOnly = true)
    public PlatformIntegrationsResponse getForSuperAdmin() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        var catalogEnv = catalogAiProperties;
        var msgEnv = messagingProperties.rapidApiWhatsApp();
        return new PlatformIntegrationsResponse(
                secrets.hasDeepseekApiKey,
                firstNonBlank(trimToNull(row.getDeepseekHost()), catalogEnv.host()),
                firstNonBlank(trimToNull(row.getDeepseekUrl()), catalogEnv.url()),
                firstNonBlank(trimToNull(row.getDeepseekModel()), catalogEnv.model()),
                secrets.hasRapidapiWhatsappKey,
                envHasDeepseekKey(catalogEnv),
                envHasRapidApiWhatsappKey(msgEnv),
                secrets.readable,
                secrets.errorMessage,
                encryptionService.usesEphemeralKey());
    }

    @Transactional
    public PlatformIntegrationsResponse update(UpdatePlatformIntegrationsRequest body) {
        PlatformIntegrationSettings row = loadSingleton();

        if (body.deepseekHost() != null) {
            row.setDeepseekHost(blankToNull(body.deepseekHost()));
        }
        if (body.deepseekUrl() != null) {
            row.setDeepseekUrl(blankToNull(body.deepseekUrl()));
        }
        if (body.deepseekModel() != null) {
            row.setDeepseekModel(blankToNull(body.deepseekModel()));
        }
        if (body.deepseekApiKey() != null) {
            row.setDeepseekApiKeyEnc(encryptOrClear(body.deepseekApiKey()));
        }
        if (body.rapidApiWhatsappKey() != null) {
            row.setRapidapiWhatsappKeyEnc(encryptOrClear(body.rapidApiWhatsappKey()));
        }
        row.setUpdatedAt(Instant.now());
        PlatformIntegrationSettings saved = repository.save(row);
        return toResponse(saved, readSecrets(saved));
    }

    @Transactional(readOnly = true)
    public ResolvedCatalogAiConfig resolveCatalogAi() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        var env = catalogAiProperties;
        String apiKey =
                secrets.readable
                        ? firstNonBlank(secrets.deepseekApiKey, env.apiKey())
                        : env.apiKey();
        String host = firstNonBlank(trimToNull(row.getDeepseekHost()), env.host());
        String url = firstNonBlank(trimToNull(row.getDeepseekUrl()), env.url());
        String model = firstNonBlank(trimToNull(row.getDeepseekModel()), env.model());
        return new ResolvedCatalogAiConfig(apiKey, host, url, model);
    }

    @Transactional(readOnly = true)
    public String resolveRapidApiWhatsappKey() {
        SecretRead secrets = readSecrets(loadSingleton());
        var envKey = messagingProperties.rapidApiWhatsApp().apiKey();
        if (!secrets.readable) {
            return blankToNull(envKey);
        }
        return firstNonBlank(secrets.rapidapiWhatsappKey, envKey);
    }

    private PlatformIntegrationsResponse toResponse(
            PlatformIntegrationSettings row,
            SecretRead secrets
    ) {
        var catalogEnv = catalogAiProperties;
        var msgEnv = messagingProperties.rapidApiWhatsApp();
        return new PlatformIntegrationsResponse(
                secrets.hasDeepseekApiKey,
                firstNonBlank(trimToNull(row.getDeepseekHost()), catalogEnv.host()),
                firstNonBlank(trimToNull(row.getDeepseekUrl()), catalogEnv.url()),
                firstNonBlank(trimToNull(row.getDeepseekModel()), catalogEnv.model()),
                secrets.hasRapidapiWhatsappKey,
                envHasDeepseekKey(catalogEnv),
                envHasRapidApiWhatsappKey(msgEnv),
                secrets.readable,
                secrets.errorMessage,
                encryptionService.usesEphemeralKey());
    }

    private PlatformIntegrationSettings loadSingleton() {
        return repository
                .findById(PlatformIntegrationSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    private PlatformIntegrationSettings createSingleton() {
        PlatformIntegrationSettings row = new PlatformIntegrationSettings();
        row.setId(PlatformIntegrationSettings.SINGLETON_ID);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    private SecretRead readSecrets(PlatformIntegrationSettings row) {
        if (encryptionService.usesEphemeralKey()) {
            boolean hasDeepseek = hasEncrypted(row.getDeepseekApiKeyEnc());
            boolean hasWhatsapp = hasEncrypted(row.getRapidapiWhatsappKeyEnc());
            return new SecretRead(
                    false,
                    hasDeepseek,
                    hasWhatsapp,
                    null,
                    null,
                    "Server encryption key is not configured; stored secrets cannot be read. "
                            + "Set APP_PAYMENTS_ENCRYPTION_KEY.");
        }
        try {
            return new SecretRead(
                    true,
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    hasEncrypted(row.getRapidapiWhatsappKeyEnc()),
                    decryptOrNull(row.getDeepseekApiKeyEnc()),
                    decryptOrNull(row.getRapidapiWhatsappKeyEnc()),
                    null);
        } catch (RuntimeException ex) {
            return new SecretRead(
                    false,
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    hasEncrypted(row.getRapidapiWhatsappKeyEnc()),
                    null,
                    null,
                    ex.getMessage());
        }
    }

    private String encryptOrClear(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return encryptionService.encryptSecret(trimmed);
    }

    private String decryptOrNull(String enc) {
        if (enc == null || enc.isBlank()) {
            return null;
        }
        return encryptionService.decrypt(enc);
    }

    private static boolean hasEncrypted(String enc) {
        return enc != null && !enc.isBlank();
    }

    private static boolean envHasDeepseekKey(CatalogAiProperties env) {
        return env.apiKey() != null && !env.apiKey().isBlank();
    }

    private static boolean envHasRapidApiWhatsappKey(
            zelisline.ub.messaging.config.MessagingProperties.RapidApiWhatsApp env
    ) {
        return env.apiKey() != null && !env.apiKey().isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }

    private static String blankToNull(String value) {
        return trimToNull(value);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private record SecretRead(
            boolean readable,
            boolean hasDeepseekApiKey,
            boolean hasRapidapiWhatsappKey,
            String deepseekApiKey,
            String rapidapiWhatsappKey,
            String errorMessage
    ) {}
}
