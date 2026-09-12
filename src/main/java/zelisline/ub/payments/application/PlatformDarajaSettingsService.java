package zelisline.ub.payments.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformDarajaSettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformDarajaSettingsRequest;
import zelisline.ub.payments.domain.PlatformDarajaSettings;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.DarajaPaymentGateway;
import zelisline.ub.payments.repository.PlatformDarajaSettingsRepository;

@Service
@RequiredArgsConstructor
public class PlatformDarajaSettingsService {

    private final PlatformDarajaSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;
    private final DarajaPaymentGateway darajaPaymentGateway;

    @Transactional(readOnly = true)
    public PlatformDarajaSettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public PlatformDarajaSettingsResponse update(UpdatePlatformDarajaSettingsRequest body) {
        PlatformDarajaSettings row = loadSingleton();
        if (body.enabled() != null) {
            row.setEnabled(body.enabled());
        }
        if (body.environment() != null && !body.environment().isBlank()) {
            row.setEnvironment(normalizeEnv(body.environment()));
        }
        if (body.shortcodeType() != null && !body.shortcodeType().isBlank()) {
            row.setShortcodeType(normalizeShortcodeType(body.shortcodeType()));
        }
        if (body.shortcode() != null) {
            String sc = digitsOnly(body.shortcode());
            row.setShortcode(sc);
        }

        if (Boolean.TRUE.equals(body.clearCredentials())) {
            row.setCredentialsEnc(null);
        } else {
            mergeCredentials(row, body);
        }

        if (row.isEnabled()) {
            requireConfigured(row);
        }

        return toResponse(repository.save(row));
    }

    @Transactional(readOnly = true)
    public boolean isEnabledAndConfigured() {
        PlatformDarajaSettings row = loadSingleton();
        return row.isEnabled() && credentialsReady(row);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, String>> credentials() {
        PlatformDarajaSettings row = loadSingleton();
        if (!row.isEnabled()) {
            return Optional.empty();
        }
        return decryptMap(row.getCredentialsEnc()).map(creds -> enrich(row, creds));
    }

    @Transactional(readOnly = true)
    public Map<String, String> requireCredentials() {
        return credentials().orElseThrow(() -> new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Platform Daraja is not enabled or credentials are missing"));
    }

    @Transactional(readOnly = true)
    public PlatformDarajaSettingsResponse testConnection() {
        PlatformDarajaSettings row = loadSingleton();
        Map<String, String> creds = decryptMap(row.getCredentialsEnc())
                .map(c -> enrich(row, c))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Save Daraja credentials before testing"));
        var result = darajaPaymentGateway.validateCredentials(creds);
        if (!result.valid()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    result.errorMessage() != null ? result.errorMessage() : "Daraja connection failed");
        }
        return toResponse(row);
    }

    @Transactional(readOnly = true)
    public PlatformDarajaSettings loadSingleton() {
        return repository.findById(PlatformDarajaSettings.SINGLETON_ID).orElseGet(() -> {
            PlatformDarajaSettings created = new PlatformDarajaSettings();
            created.setId(PlatformDarajaSettings.SINGLETON_ID);
            return repository.save(created);
        });
    }

    private void mergeCredentials(PlatformDarajaSettings row, UpdatePlatformDarajaSettingsRequest body) {
        if (body.consumerKey() == null
                && body.consumerSecret() == null
                && body.passkey() == null) {
            // Still sync shortcode / env into encrypted blob when present.
            if (row.getCredentialsEnc() == null || row.getCredentialsEnc().isBlank()) {
                return;
            }
            Map<String, String> existing = decryptMap(row.getCredentialsEnc()).orElseGet(LinkedHashMap::new);
            row.setCredentialsEnc(encryptionService.encrypt(writeJson(enrich(row, existing))));
            return;
        }
        Map<String, String> merged = decryptMap(row.getCredentialsEnc()).orElseGet(LinkedHashMap::new);
        if (body.consumerKey() != null && !body.consumerKey().isBlank()) {
            merged.put("consumerKey", body.consumerKey().trim());
        }
        if (body.consumerSecret() != null && !body.consumerSecret().isBlank()) {
            merged.put("consumerSecret", body.consumerSecret().trim());
        }
        if (body.passkey() != null && !body.passkey().isBlank()) {
            merged.put("passkey", body.passkey().trim());
        }
        row.setCredentialsEnc(encryptionService.encrypt(writeJson(enrich(row, merged))));
    }

    private static Map<String, String> enrich(PlatformDarajaSettings row, Map<String, String> creds) {
        Map<String, String> out = new LinkedHashMap<>(creds);
        out.put("environment", row.getEnvironment());
        out.put("shortcodeType", row.getShortcodeType());
        if (row.getShortcode() != null && !row.getShortcode().isBlank()) {
            out.put("shortcode", row.getShortcode());
        }
        return out;
    }

    private void requireConfigured(PlatformDarajaSettings row) {
        if (!credentialsReady(row)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Enable requires consumer key, secret, passkey, and shortcode");
        }
    }

    private boolean credentialsReady(PlatformDarajaSettings row) {
        if (row.getShortcode() == null || row.getShortcode().isBlank()) {
            return false;
        }
        Map<String, String> creds = decryptMap(row.getCredentialsEnc()).orElse(Map.of());
        return isPresent(creds, "consumerKey")
                && isPresent(creds, "consumerSecret")
                && isPresent(creds, "passkey");
    }

    private PlatformDarajaSettingsResponse toResponse(PlatformDarajaSettings row) {
        Map<String, String> creds = decryptMap(row.getCredentialsEnc()).orElse(Map.of());
        String key = creds.get("consumerKey");
        String hint = key != null && key.length() > 10
                ? key.substring(0, 8) + "…"
                : (key != null && !key.isBlank() ? "••••" : null);
        return new PlatformDarajaSettingsResponse(
                row.isEnabled(),
                row.getEnvironment(),
                row.getShortcodeType(),
                row.getShortcode(),
                row.getCredentialsEnc() != null && !row.getCredentialsEnc().isBlank(),
                hint,
                row.getUpdatedAt() != null ? row.getUpdatedAt() : Instant.now());
    }

    private Optional<Map<String, String>> decryptMap(String enc) {
        if (enc == null || enc.isBlank()) {
            return Optional.empty();
        }
        try {
            String json = encryptionService.decrypt(enc);
            Map<String, String> map = objectMapper.readValue(json, new TypeReference<>() {
            });
            return Optional.of(new LinkedHashMap<>(map));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String writeJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize credentials");
        }
    }

    private static boolean isPresent(Map<String, String> map, String key) {
        String v = map.get(key);
        return v != null && !v.isBlank();
    }

    private static String normalizeEnv(String env) {
        return "production".equalsIgnoreCase(env.trim()) ? "production" : "sandbox";
    }

    private static String normalizeShortcodeType(String type) {
        String t = type.trim().toLowerCase();
        if ("till".equals(t) || "buygoods".equals(t) || "buy_goods".equals(t)) {
            return "till";
        }
        return "paybill";
    }

    private static String digitsOnly(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.isBlank() ? null : digits;
    }
}
