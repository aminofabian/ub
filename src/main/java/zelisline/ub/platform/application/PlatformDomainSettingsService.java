package zelisline.ub.platform.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.PlatformDomainSettingsResponse;
import zelisline.ub.platform.api.dto.UpdatePlatformDomainSettingsRequest;
import zelisline.ub.platform.domain.PlatformDomainSettings;
import zelisline.ub.platform.repository.PlatformDomainSettingsRepository;
import zelisline.ub.tenancy.integrations.hostafrica.HostAfricaProperties;
import zelisline.ub.tenancy.integrations.vercel.VercelProperties;

@Service
@RequiredArgsConstructor
public class PlatformDomainSettingsService {

    public static final String PLATFORM_DOMAIN_STK_CONFIG_ID = "platform-domain-stk";

    private static final String DEFAULT_HA_BASE = "https://api.hostafrica.com";
    private static final String DEFAULT_HA_CURRENCY = "KES";
    private static final String DEFAULT_HA_TLDS = "co.ke,or.ke,me.ke,sc.ke,ac.ke,go.ke,ke";
    private static final String DEFAULT_VERCEL_BASE = "https://api.vercel.com";
    public static final String DEFAULT_RESELLER_BASE =
            "https://my.hostafrica.com/modules/addons/DomainsReseller/api/index.php";

    private static final String[] WHOIS_REQUIRED = {
            "firstname", "lastname", "companyname", "email",
            "address1", "city", "state", "postcode", "country", "phonenumber"
    };

    private final PlatformDomainSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final HostAfricaProperties hostAfricaEnv;
    private final VercelProperties vercelEnv;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlatformDomainSettingsResponse getForSuperAdmin() {
        PlatformDomainSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);
        return toResponse(row, secrets);
    }

    @Transactional
    public PlatformDomainSettingsResponse update(UpdatePlatformDomainSettingsRequest body) {
        PlatformDomainSettings row = loadSingleton();

        if (body.hostafricaApiKey() != null) {
            row.setHostafricaApiKeyEnc(encryptOrClear(body.hostafricaApiKey()));
        }
        if (body.hostafricaApiBaseUrl() != null) {
            row.setHostafricaApiBaseUrl(blankToNull(body.hostafricaApiBaseUrl()));
        }
        if (body.hostafricaCurrency() != null) {
            row.setHostafricaCurrency(blankToNull(body.hostafricaCurrency()));
        }
        if (body.hostafricaKenyanTlds() != null) {
            row.setHostafricaKenyanTlds(blankToNull(body.hostafricaKenyanTlds()));
        }
        if (body.hostafricaBillingStubEnabled() != null) {
            row.setHostafricaBillingStubEnabled(body.hostafricaBillingStubEnabled());
        }
        if (body.hostafricaRegistrantDefaults() != null) {
            row.setHostafricaRegistrantDefaultsJson(writeRegistrantDefaults(body.hostafricaRegistrantDefaults()));
        }

        if (body.hostafricaResellerEmail() != null) {
            row.setHostafricaResellerEmail(blankToNull(body.hostafricaResellerEmail()));
        }
        if (Boolean.TRUE.equals(body.clearHostafricaResellerApiKey())) {
            row.setHostafricaResellerApiKeyEnc(null);
        } else if (body.hostafricaResellerApiKey() != null) {
            row.setHostafricaResellerApiKeyEnc(encryptOrClear(body.hostafricaResellerApiKey()));
        }
        if (body.hostafricaResellerApiBaseUrl() != null) {
            row.setHostafricaResellerApiBaseUrl(blankToNull(body.hostafricaResellerApiBaseUrl()));
        }
        if (body.hostafricaResellerWhois() != null) {
            row.setHostafricaResellerWhoisJson(writeWhois(body.hostafricaResellerWhois()));
        }

        if (Boolean.TRUE.equals(body.clearPalmartStkCredentials())) {
            row.setPalmartStkCredentialsEnc(null);
            row.setPalmartStkTillNumber(null);
        } else {
            mergePalmartStkCredentials(row, body);
        }

        if (body.vercelToken() != null) {
            row.setVercelTokenEnc(encryptOrClear(body.vercelToken()));
        }
        if (body.vercelTeamId() != null) {
            row.setVercelTeamId(blankToNull(body.vercelTeamId()));
        }
        if (body.vercelProjectId() != null) {
            row.setVercelProjectId(blankToNull(body.vercelProjectId()));
        }
        if (body.vercelApiBaseUrl() != null) {
            row.setVercelApiBaseUrl(blankToNull(body.vercelApiBaseUrl()));
        }

        if (body.domainOrderSyncEnabled() != null) {
            row.setDomainOrderSyncEnabled(body.domainOrderSyncEnabled());
        }
        if (body.domainOrderSyncFixedDelayMs() != null) {
            int ms = Math.max(5_000, Math.min(3_600_000, body.domainOrderSyncFixedDelayMs()));
            row.setDomainOrderSyncFixedDelayMs(ms);
        }
        if (body.domainOrderSyncInitialDelayMs() != null) {
            int ms = Math.max(0, Math.min(3_600_000, body.domainOrderSyncInitialDelayMs()));
            row.setDomainOrderSyncInitialDelayMs(ms);
        }

        row.setUpdatedAt(Instant.now());
        PlatformDomainSettings saved = repository.save(row);
        return toResponse(saved, readSecrets(saved));
    }

    /** Runtime resolve for HostAfrica / Vercel clients — DB preferred over env. */
    @Transactional(readOnly = true)
    public ResolvedDomainIntegrationsConfig resolve() {
        PlatformDomainSettings row = loadSingleton();
        SecretRead secrets = readSecrets(row);

        String haKey;
        if (hasEncrypted(row.getHostafricaApiKeyEnc())) {
            haKey = secrets.readable ? secrets.hostafricaApiKey : null;
        } else {
            haKey = blankToNull(hostAfricaEnv.getApiKey());
        }

        String vercelToken;
        if (hasEncrypted(row.getVercelTokenEnc())) {
            vercelToken = secrets.readable ? secrets.vercelToken : null;
        } else {
            vercelToken = blankToNull(vercelEnv.getToken());
        }

        return new ResolvedDomainIntegrationsConfig(
                haKey,
                firstNonBlank(
                        trimToNull(row.getHostafricaApiBaseUrl()),
                        blankToNull(hostAfricaEnv.getApiBaseUrl()),
                        DEFAULT_HA_BASE),
                firstNonBlank(
                        trimToNull(row.getHostafricaCurrency()),
                        blankToNull(hostAfricaEnv.getCurrency()),
                        DEFAULT_HA_CURRENCY),
                firstNonBlank(
                        trimToNull(row.getHostafricaKenyanTlds()),
                        blankToNull(hostAfricaEnv.getKenyanTlds()),
                        DEFAULT_HA_TLDS),
                row.isHostafricaBillingStubEnabled(),
                vercelToken,
                firstNonBlank(trimToNull(row.getVercelTeamId()), blankToNull(vercelEnv.getTeamId()), ""),
                firstNonBlank(trimToNull(row.getVercelProjectId()), blankToNull(vercelEnv.getProjectId()), ""),
                firstNonBlank(
                        trimToNull(row.getVercelApiBaseUrl()),
                        blankToNull(vercelEnv.getApiBaseUrl()),
                        DEFAULT_VERCEL_BASE),
                row.isDomainOrderSyncEnabled(),
                row.getDomainOrderSyncFixedDelayMs() > 0
                        ? row.getDomainOrderSyncFixedDelayMs()
                        : 60_000,
                Math.max(0, row.getDomainOrderSyncInitialDelayMs())
        );
    }

    /** Decrypted Palmart KopoKopo credentials for domain-order STK, or empty if not configured. */
    @Transactional(readOnly = true)
    public Map<String, String> resolvePalmartStkCredentials() {
        PlatformDomainSettings row = loadSingleton();
        if (!hasEncrypted(row.getPalmartStkCredentialsEnc())) {
            return Map.of();
        }
        try {
            String decrypted = encryptionService.decrypt(row.getPalmartStkCredentialsEnc());
            Map<String, String> creds = objectMapper.readValue(decrypted, new TypeReference<>() {});
            return creds == null ? Map.of() : Map.copyOf(creds);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Key→value map for HostAfrica save-domain-required-data (additionalFields names). */
    @Transactional(readOnly = true)
    public Map<String, String> resolveRegistrantDefaults() {
        PlatformDomainSettings row = loadSingleton();
        return parseRegistrantDefaults(row.getHostafricaRegistrantDefaultsJson());
    }

    @Transactional(readOnly = true)
    public boolean palmartStkConfigured() {
        Map<String, String> creds = resolvePalmartStkCredentials();
        if (creds.isEmpty()) {
            return false;
        }
        String till = firstNonBlank(creds.get("tillNumber"), creds.get("shortcode"));
        String clientId = creds.get("clientId");
        String clientSecret = creds.get("clientSecret");
        return till != null && !till.isBlank()
                && clientId != null && !clientId.isBlank()
                && clientSecret != null && !clientSecret.isBlank();
    }

    /** DomainsReseller RegisterDomain credentials + WHOIS ready. */
    @Transactional(readOnly = true)
    public boolean resellerConfigured() {
        ResolvedResellerConfig cfg = resolveReseller();
        return cfg.configured();
    }

    @Transactional(readOnly = true)
    public ResolvedResellerConfig resolveReseller() {
        PlatformDomainSettings row = loadSingleton();
        String email = trimToNull(row.getHostafricaResellerEmail());
        String apiKey = null;
        if (hasEncrypted(row.getHostafricaResellerApiKeyEnc())) {
            apiKey = decryptOrNull(row.getHostafricaResellerApiKeyEnc());
        }
        String base = firstNonBlank(
                trimToNull(row.getHostafricaResellerApiBaseUrl()),
                DEFAULT_RESELLER_BASE);
        Map<String, String> whois = parseWhois(row.getHostafricaResellerWhoisJson());
        return new ResolvedResellerConfig(email, apiKey, base, whois);
    }

    private void mergePalmartStkCredentials(PlatformDomainSettings row, UpdatePlatformDomainSettingsRequest body) {
        boolean anyStkField = body.palmartStkClientId() != null
                || body.palmartStkClientSecret() != null
                || body.palmartStkApiKey() != null
                || body.palmartStkTillNumber() != null
                || body.palmartStkEnvironment() != null;
        if (!anyStkField) {
            return;
        }

        Map<String, String> existing = new LinkedHashMap<>();
        if (hasEncrypted(row.getPalmartStkCredentialsEnc())) {
            try {
                String decrypted = encryptionService.decrypt(row.getPalmartStkCredentialsEnc());
                Map<String, String> parsed = objectMapper.readValue(decrypted, new TypeReference<>() {});
                if (parsed != null) {
                    existing.putAll(parsed);
                }
            } catch (Exception ignored) {
                // start fresh if corrupt
            }
        }

        putIfProvided(existing, "clientId", body.palmartStkClientId());
        putIfProvided(existing, "clientSecret", body.palmartStkClientSecret());
        putIfProvided(existing, "apiKey", body.palmartStkApiKey());
        putIfProvided(existing, "tillNumber", body.palmartStkTillNumber());
        putIfProvided(existing, "environment", body.palmartStkEnvironment());

        if (existing.isEmpty()
                || isBlank(existing.get("clientId"))
                || isBlank(existing.get("clientSecret"))
                || isBlank(firstNonBlank(existing.get("tillNumber"), existing.get("shortcode")))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Palmart STK requires clientId, clientSecret, and tillNumber"
            );
        }

        try {
            row.setPalmartStkCredentialsEnc(encryptionService.encrypt(objectMapper.writeValueAsString(existing)));
            row.setPalmartStkTillNumber(blankToNull(
                    firstNonBlank(existing.get("tillNumber"), existing.get("shortcode"))));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not store Palmart STK credentials", ex);
        }
    }

    private static void putIfProvided(Map<String, String> map, String key, String value) {
        if (value == null) {
            return;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, trimmed);
        }
    }

    private PlatformDomainSettingsResponse toResponse(PlatformDomainSettings row, SecretRead secrets) {
        Map<String, String> whois = parseWhois(row.getHostafricaResellerWhoisJson());
        boolean resellerKey = secrets.hasResellerApiKey;
        String resellerEmail = firstNonBlank(trimToNull(row.getHostafricaResellerEmail()), "");
        boolean resellerReady = !isBlank(resellerEmail)
                && resellerKey
                && whoisComplete(whois);
        return new PlatformDomainSettingsResponse(
                secrets.hasHostafricaApiKey,
                firstNonBlank(
                        trimToNull(row.getHostafricaApiBaseUrl()),
                        blankToNull(hostAfricaEnv.getApiBaseUrl()),
                        DEFAULT_HA_BASE),
                firstNonBlank(
                        trimToNull(row.getHostafricaCurrency()),
                        blankToNull(hostAfricaEnv.getCurrency()),
                        DEFAULT_HA_CURRENCY),
                firstNonBlank(
                        trimToNull(row.getHostafricaKenyanTlds()),
                        blankToNull(hostAfricaEnv.getKenyanTlds()),
                        DEFAULT_HA_TLDS),
                row.isHostafricaBillingStubEnabled(),
                parseRegistrantDefaults(row.getHostafricaRegistrantDefaultsJson()),
                resellerKey,
                resellerEmail,
                firstNonBlank(trimToNull(row.getHostafricaResellerApiBaseUrl()), DEFAULT_RESELLER_BASE),
                resellerReady,
                whois,
                secrets.hasPalmartStk,
                firstNonBlank(trimToNull(row.getPalmartStkTillNumber()), ""),
                secrets.hasVercelToken,
                firstNonBlank(trimToNull(row.getVercelTeamId()), blankToNull(vercelEnv.getTeamId()), ""),
                firstNonBlank(trimToNull(row.getVercelProjectId()), blankToNull(vercelEnv.getProjectId()), ""),
                firstNonBlank(
                        trimToNull(row.getVercelApiBaseUrl()),
                        blankToNull(vercelEnv.getApiBaseUrl()),
                        DEFAULT_VERCEL_BASE),
                row.isDomainOrderSyncEnabled(),
                row.getDomainOrderSyncFixedDelayMs(),
                row.getDomainOrderSyncInitialDelayMs(),
                hostAfricaEnv.configured(),
                vercelEnv.configured(),
                secrets.readable,
                secrets.errorMessage,
                encryptionService.usesEphemeralKey(),
                row.getUpdatedAt()
        );
    }

    private PlatformDomainSettings loadSingleton() {
        return repository
                .findById(PlatformDomainSettings.SINGLETON_ID)
                .orElseGet(this::createSingleton);
    }

    private PlatformDomainSettings createSingleton() {
        PlatformDomainSettings row = new PlatformDomainSettings();
        row.setId(PlatformDomainSettings.SINGLETON_ID);
        row.setUpdatedAt(Instant.now());
        return repository.save(row);
    }

    private SecretRead readSecrets(PlatformDomainSettings row) {
        String persistenceHint = null;
        if (encryptionService.usesEphemeralKey()) {
            persistenceHint =
                    "APP_PAYMENTS_ENCRYPTION_KEY is not set; stored secrets work until the next "
                            + "restart, then must be re-saved. Set the key in production.";
        }
        try {
            return new SecretRead(
                    true,
                    hasEncrypted(row.getHostafricaApiKeyEnc()),
                    hasEncrypted(row.getVercelTokenEnc()),
                    hasEncrypted(row.getPalmartStkCredentialsEnc()),
                    hasEncrypted(row.getHostafricaResellerApiKeyEnc()),
                    decryptOrNull(row.getHostafricaApiKeyEnc()),
                    decryptOrNull(row.getVercelTokenEnc()),
                    persistenceHint);
        } catch (RuntimeException ex) {
            return new SecretRead(
                    false,
                    hasEncrypted(row.getHostafricaApiKeyEnc()),
                    hasEncrypted(row.getVercelTokenEnc()),
                    hasEncrypted(row.getPalmartStkCredentialsEnc()),
                    hasEncrypted(row.getHostafricaResellerApiKeyEnc()),
                    null,
                    null,
                    firstNonBlank(ex.getMessage(), persistenceHint));
        }
    }

    private String writeRegistrantDefaults(Map<String, String> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            return null;
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : defaults.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank()) {
                continue;
            }
            String value = e.getValue() == null ? "" : e.getValue().trim();
            if (value.isEmpty()) {
                continue;
            }
            cleaned.put(e.getKey().trim(), value);
        }
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(cleaned);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not store registrant defaults", ex);
        }
    }

    private Map<String, String> parseRegistrantDefaults(String raw) {
        return parseStringMap(raw);
    }

    private String writeWhois(Map<String, String> whois) {
        return writeRegistrantDefaults(whois);
    }

    private Map<String, String> parseWhois(String raw) {
        return parseStringMap(raw);
    }

    private Map<String, String> parseStringMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
            if (parsed == null || parsed.isEmpty()) {
                return Map.of();
            }
            Map<String, String> cleaned = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : parsed.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                if (e.getValue() == null || e.getValue().isBlank()) {
                    continue;
                }
                cleaned.put(e.getKey().trim(), e.getValue().trim());
            }
            return Map.copyOf(cleaned);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static boolean whoisComplete(Map<String, String> whois) {
        if (whois == null || whois.isEmpty()) {
            return false;
        }
        for (String key : WHOIS_REQUIRED) {
            String v = whois.get(key);
            if (v == null || v.isBlank()) {
                // Accept fullname as substitute for firstname+lastname when both missing is rare;
                // require firstname and lastname explicitly.
                return false;
            }
        }
        return true;
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
        try {
            return encryptionService.decrypt(enc);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasEncrypted(String enc) {
        return enc != null && !enc.isBlank();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToNull(String value) {
        return blankToNull(value);
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }

    private record SecretRead(
            boolean readable,
            boolean hasHostafricaApiKey,
            boolean hasVercelToken,
            boolean hasPalmartStk,
            boolean hasResellerApiKey,
            String hostafricaApiKey,
            String vercelToken,
            String errorMessage
    ) {}

    /** Runtime DomainsReseller config for RegisterDomain. */
    public record ResolvedResellerConfig(
            String email,
            String apiKey,
            String apiBaseUrl,
            Map<String, String> whois
    ) {
        public boolean configured() {
            return email != null && !email.isBlank()
                    && apiKey != null && !apiKey.isBlank()
                    && apiBaseUrl != null && !apiBaseUrl.isBlank()
                    && whoisComplete(whois);
        }
    }
}
