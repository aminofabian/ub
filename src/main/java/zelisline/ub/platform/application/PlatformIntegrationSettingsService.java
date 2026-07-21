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

    private static final String DEFAULT_PHONE_FIELD = "phone";
    private static final String DEFAULT_SOZURI_URL = "https://sozuri.net/api/v1/messaging";
    private static final String DEFAULT_TEXTSMS_URL = "https://sms.textsms.co.ke/api/services/sendsms/";

    private final PlatformIntegrationSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final CatalogAiProperties catalogAiProperties;
    private final MessagingProperties messagingProperties;

    @Transactional(readOnly = true)
    public PlatformIntegrationsResponse getForSuperAdmin() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        return toResponse(row, secrets);
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
        if (body.rapidApiWhatsappHost() != null) {
            row.setRapidapiWhatsappHost(blankToNull(body.rapidApiWhatsappHost()));
        }
        if (body.rapidApiWhatsappLookupUrl() != null) {
            row.setRapidapiWhatsappLookupUrl(blankToNull(body.rapidApiWhatsappLookupUrl()));
        }
        if (body.rapidApiWhatsappPhoneField() != null) {
            row.setRapidapiWhatsappPhoneField(blankToNull(body.rapidApiWhatsappPhoneField()));
        }
        if (body.rapidApiWhatsappPhoneDigitsOnly() != null) {
            row.setRapidapiWhatsappPhoneDigitsOnly(body.rapidApiWhatsappPhoneDigitsOnly());
        }
        if (body.smsProvider() != null) {
            String provider = blankToNull(body.smsProvider());
            if (provider != null) {
                provider = provider.toLowerCase();
                if (!"none".equals(provider)
                        && !"africas_talking".equals(provider)
                        && !"sozuri".equals(provider)
                        && !"textsms".equals(provider)) {
                    provider = "none";
                }
            }
            row.setSmsProvider(provider);
        }
        if (body.sozuriProject() != null) {
            row.setSozuriProject(blankToNull(body.sozuriProject()));
        }
        if (body.sozuriApiKey() != null) {
            row.setSozuriApiKeyEnc(encryptOrClear(body.sozuriApiKey()));
        }
        if (body.sozuriFrom() != null) {
            row.setSozuriFrom(blankToNull(body.sozuriFrom()));
        }
        if (body.sozuriType() != null) {
            String type = blankToNull(body.sozuriType());
            if (type != null) {
                type = type.toLowerCase();
                if (!"transactional".equals(type) && !"promotional".equals(type)) {
                    type = "transactional";
                }
            }
            row.setSozuriType(type);
        }
        if (body.sozuriApiUrl() != null) {
            row.setSozuriApiUrl(blankToNull(body.sozuriApiUrl()));
        }
        if (body.textsmsPartnerId() != null) {
            row.setTextsmsPartnerId(blankToNull(body.textsmsPartnerId()));
        }
        if (body.textsmsApiKey() != null) {
            row.setTextsmsApiKeyEnc(encryptOrClear(body.textsmsApiKey()));
        }
        if (body.textsmsShortcode() != null) {
            row.setTextsmsShortcode(blankToNull(body.textsmsShortcode()));
        }
        if (body.textsmsApiUrl() != null) {
            row.setTextsmsApiUrl(blankToNull(body.textsmsApiUrl()));
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
        return resolveRapidApiWhatsapp().apiKey();
    }

    @Transactional(readOnly = true)
    public ResolvedRapidApiWhatsappConfig resolveRapidApiWhatsapp() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        var env = messagingProperties.rapidApiWhatsApp();
        return resolveWhatsappFromRow(row, secrets, env);
    }

    @Transactional(readOnly = true)
    public ResolvedSozuriSmsConfig resolveSozuriSms() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        return resolveSozuriFromRow(row, secrets);
    }

    @Transactional(readOnly = true)
    public ResolvedTextSmsConfig resolveTextSms() {
        PlatformIntegrationSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        return resolveTextSmsFromRow(row, secrets);
    }

    private PlatformIntegrationsResponse toResponse(
            PlatformIntegrationSettings row,
            SecretRead secrets
    ) {
        var catalogEnv = catalogAiProperties;
        var msgEnv = messagingProperties.rapidApiWhatsApp();
        ResolvedRapidApiWhatsappConfig wa = resolveWhatsappFromRow(row, secrets, msgEnv);
        ResolvedSozuriSmsConfig sozuri = resolveSozuriFromRow(row, secrets);
        ResolvedTextSmsConfig textsms = resolveTextSmsFromRow(row, secrets);
        return new PlatformIntegrationsResponse(
                secrets.hasDeepseekApiKey,
                firstNonBlank(trimToNull(row.getDeepseekHost()), catalogEnv.host()),
                firstNonBlank(trimToNull(row.getDeepseekUrl()), catalogEnv.url()),
                firstNonBlank(trimToNull(row.getDeepseekModel()), catalogEnv.model()),
                secrets.hasRapidapiWhatsappKey,
                wa.host(),
                wa.lookupUrl(),
                wa.phoneField(),
                wa.phoneDigitsOnly(),
                sozuri.provider(),
                sozuri.project(),
                sozuri.from(),
                sozuri.type(),
                sozuri.apiUrl(),
                secrets.hasSozuriApiKey,
                textsms.partnerId(),
                textsms.shortcode(),
                textsms.apiUrl(),
                secrets.hasTextsmsApiKey,
                envHasDeepseekKey(catalogEnv),
                envHasRapidApiWhatsappKey(msgEnv),
                envHasSozuriKey(messagingProperties.sms()),
                envHasTextsmsKey(messagingProperties.sms()),
                secrets.readable,
                secrets.errorMessage,
                encryptionService.usesEphemeralKey());
    }

    private ResolvedRapidApiWhatsappConfig resolveWhatsappFromRow(
            PlatformIntegrationSettings row,
            SecretRead secrets,
            MessagingProperties.RapidApiWhatsApp env
    ) {
        String apiKey =
                secrets.readable
                        ? firstNonBlank(secrets.rapidapiWhatsappKey, env.apiKey())
                        : blankToNull(env.apiKey());
        String host = firstNonBlank(trimToNull(row.getRapidapiWhatsappHost()), env.host());
        String lookupUrl =
                firstNonBlank(trimToNull(row.getRapidapiWhatsappLookupUrl()), env.lookupUrl());
        String phoneField =
                firstNonBlank(trimToNull(row.getRapidapiWhatsappPhoneField()), DEFAULT_PHONE_FIELD);
        boolean digitsOnly =
                row.getRapidapiWhatsappPhoneDigitsOnly() != null
                        && row.getRapidapiWhatsappPhoneDigitsOnly();
        return new ResolvedRapidApiWhatsappConfig(apiKey, host, lookupUrl, phoneField, digitsOnly);
    }

    private ResolvedSozuriSmsConfig resolveSozuriFromRow(
            PlatformIntegrationSettings row,
            SecretRead secrets
    ) {
        var env = messagingProperties.sms();
        String provider = firstNonBlank(trimToNull(row.getSmsProvider()), env.provider(), "none");
        String project = firstNonBlank(trimToNull(row.getSozuriProject()), env.sozuriProject());
        String apiKey =
                secrets.readable
                        ? firstNonBlank(secrets.sozuriApiKey, env.sozuriApiKey())
                        : blankToNull(env.sozuriApiKey());
        String from = firstNonBlank(trimToNull(row.getSozuriFrom()), env.sozuriFrom(), "Sozuri");
        String type =
                firstNonBlank(trimToNull(row.getSozuriType()), env.sozuriType(), "transactional");
        String apiUrl =
                firstNonBlank(trimToNull(row.getSozuriApiUrl()), env.sozuriApiUrl(), DEFAULT_SOZURI_URL);
        return new ResolvedSozuriSmsConfig(provider, project, apiKey, from, type, apiUrl);
    }

    private ResolvedTextSmsConfig resolveTextSmsFromRow(
            PlatformIntegrationSettings row,
            SecretRead secrets
    ) {
        var env = messagingProperties.sms();
        String partnerId =
                firstNonBlank(trimToNull(row.getTextsmsPartnerId()), env.textsmsPartnerId());
        String apiKey =
                secrets.readable
                        ? firstNonBlank(secrets.textsmsApiKey, env.textsmsApiKey())
                        : blankToNull(env.textsmsApiKey());
        String shortcode =
                firstNonBlank(trimToNull(row.getTextsmsShortcode()), env.textsmsShortcode());
        String apiUrl =
                firstNonBlank(trimToNull(row.getTextsmsApiUrl()), env.textsmsApiUrl(), DEFAULT_TEXTSMS_URL);
        return new ResolvedTextSmsConfig(partnerId, apiKey, shortcode, apiUrl);
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
            return new SecretRead(
                    false,
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    hasEncrypted(row.getRapidapiWhatsappKeyEnc()),
                    hasEncrypted(row.getSozuriApiKeyEnc()),
                    hasEncrypted(row.getTextsmsApiKeyEnc()),
                    null,
                    null,
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
                    hasEncrypted(row.getSozuriApiKeyEnc()),
                    hasEncrypted(row.getTextsmsApiKeyEnc()),
                    decryptOrNull(row.getDeepseekApiKeyEnc()),
                    decryptOrNull(row.getRapidapiWhatsappKeyEnc()),
                    decryptOrNull(row.getSozuriApiKeyEnc()),
                    decryptOrNull(row.getTextsmsApiKeyEnc()),
                    null);
        } catch (RuntimeException ex) {
            return new SecretRead(
                    false,
                    hasEncrypted(row.getDeepseekApiKeyEnc()),
                    hasEncrypted(row.getRapidapiWhatsappKeyEnc()),
                    hasEncrypted(row.getSozuriApiKeyEnc()),
                    hasEncrypted(row.getTextsmsApiKeyEnc()),
                    null,
                    null,
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
            MessagingProperties.RapidApiWhatsApp env
    ) {
        return env.apiKey() != null && !env.apiKey().isBlank();
    }

    private static boolean envHasSozuriKey(MessagingProperties.Sms env) {
        return env.sozuriApiKey() != null && !env.sozuriApiKey().isBlank();
    }

    private static boolean envHasTextsmsKey(MessagingProperties.Sms env) {
        return env.textsmsApiKey() != null && !env.textsmsApiKey().isBlank();
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

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }

    private record SecretRead(
            boolean readable,
            boolean hasDeepseekApiKey,
            boolean hasRapidapiWhatsappKey,
            boolean hasSozuriApiKey,
            boolean hasTextsmsApiKey,
            String deepseekApiKey,
            String rapidapiWhatsappKey,
            String sozuriApiKey,
            String textsmsApiKey,
            String errorMessage
    ) {}
}
