package zelisline.ub.airtime.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.airtime.api.dto.PlatformAirtimeSettingsResponse;
import zelisline.ub.airtime.api.dto.UpdatePlatformAirtimeSettingsRequest;
import zelisline.ub.airtime.domain.PlatformAirtimeSettings;
import zelisline.ub.airtime.infrastructure.InstalipaAirtimeGateway;
import zelisline.ub.airtime.repository.PlatformAirtimeSettingsRepository;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;

/**
 * Platform airtime configuration. Instalipa credentials live here, encrypted,
 * and are never returned to any client — only a masked hint and a boolean.
 */
@Service
@RequiredArgsConstructor
public class PlatformAirtimeSettingsService {

    private static final BigDecimal MAX_COMMISSION_PERCENT = new BigDecimal("25.000");

    private final PlatformAirtimeSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final InstalipaAirtimeGateway gateway;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public PlatformAirtimeSettingsResponse getForSuperAdmin() {
        return toResponse(loadSingleton());
    }

    @Transactional
    public PlatformAirtimeSettingsResponse update(UpdatePlatformAirtimeSettingsRequest body) {
        PlatformAirtimeSettings row = loadSingleton();

        if (body.baseUrl() != null && !body.baseUrl().isBlank()) {
            String base = body.baseUrl().trim();
            if (!base.startsWith("https://")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Instalipa base URL must use HTTPS");
            }
            row.setBaseUrl(base.replaceAll("/+$", ""));
        }
        if (body.environment() != null && !body.environment().isBlank()) {
            row.setEnvironment(normalizeEnv(body.environment()));
        }
        if (body.currency() != null && !body.currency().isBlank()) {
            row.setCurrency(body.currency().trim().toUpperCase());
        }

        BigDecimal commission = body.tenantCommissionPercent();
        if (commission != null) {
            if (commission.compareTo(BigDecimal.ZERO) < 0
                    || commission.compareTo(MAX_COMMISSION_PERCENT) > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Tenant commission must be between 0 and " + MAX_COMMISSION_PERCENT + "%");
            }
            row.setTenantCommissionPercent(commission);
        }

        BigDecimal min = body.minAmount();
        BigDecimal max = body.maxAmount();
        if (min != null && min.compareTo(BigDecimal.ONE) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Minimum airtime must be at least 1");
        }
        if (max != null && max.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Maximum airtime must be positive");
        }
        BigDecimal effectiveMin = min != null ? min : row.getMinAmount();
        BigDecimal effectiveMax = max != null ? max : row.getMaxAmount();
        if (effectiveMin.compareTo(effectiveMax) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Minimum airtime cannot exceed the maximum");
        }
        if (min != null) {
            row.setMinAmount(min);
        }
        if (max != null) {
            row.setMaxAmount(max);
        }

        if (body.dailyTenantLimit() != null) {
            if (body.dailyTenantLimit().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Daily tenant limit must be positive");
            }
            row.setDailyTenantLimit(body.dailyTenantLimit());
        }
        if (body.floatLowThreshold() != null) {
            if (body.floatLowThreshold().compareTo(BigDecimal.ZERO) < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Float alert threshold cannot be negative");
            }
            row.setFloatLowThreshold(body.floatLowThreshold());
        }
        if (body.posEnabled() != null) {
            row.setPosEnabled(body.posEnabled());
        }
        if (body.storefrontEnabled() != null) {
            row.setStorefrontEnabled(body.storefrontEnabled());
        }
        if (Boolean.TRUE.equals(body.clearFloatConstraint())) {
            row.setFloatConstrainedUntil(null);
        }

        if (Boolean.TRUE.equals(body.clearCredentials())) {
            row.setCredentialsEnc(null);
        } else {
            mergeCredentials(row, body);
        }

        // Turning the product on without working credentials would only surface as a
        // failed sale at somebody's till, so verify before flipping the switch.
        if (Boolean.TRUE.equals(body.enabled())) {
            Map<String, String> creds = decryptMap(row.getCredentialsEnc()).orElseThrow(
                    () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Save Instalipa credentials before enabling airtime"));
            String failure = gateway.validateCredentials(creds, row.getBaseUrl());
            if (failure != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Instalipa credentials did not work: " + failure);
            }
            row.setEnabled(true);
        } else if (Boolean.FALSE.equals(body.enabled())) {
            row.setEnabled(false);
        }

        return toResponse(repository.save(row));
    }

    /** Ask Instalipa for the current float by pricing nothing — a token round trip. */
    @Transactional
    public PlatformAirtimeSettingsResponse testConnection() {
        PlatformAirtimeSettings row = loadSingleton();
        Map<String, String> creds = decryptMap(row.getCredentialsEnc()).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Instalipa credentials are not configured"));
        String failure = gateway.validateCredentials(creds, row.getBaseUrl());
        if (failure != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, failure);
        }
        row.setFloatCheckedAt(Instant.now());
        return toResponse(repository.save(row));
    }

    /**
     * Record the float Instalipa reports on every response. Runs in its own
     * transaction so a bookkeeping write can never roll back an airtime sale.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFloatBalance(BigDecimal balance) {
        if (balance == null) {
            return;
        }
        PlatformAirtimeSettings row = loadSingleton();
        row.setFloatBalance(balance);
        row.setFloatCheckedAt(Instant.now());
        repository.save(row);
    }

    /**
     * Instalipa refused because the platform float is dry. Pause sends so tills
     * fail fast with a clear message instead of hammering the provider.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFloatConstrained(Duration cooldown) {
        PlatformAirtimeSettings row = loadSingleton();
        row.setFloatConstrainedUntil(Instant.now().plus(cooldown));
        repository.save(row);
    }

    @Transactional(readOnly = true)
    public Optional<Map<String, String>> credentials() {
        return decryptMap(loadSingleton().getCredentialsEnc());
    }

    @Transactional(readOnly = true)
    public boolean isProductEnabled() {
        return loadSingleton().isEnabled();
    }

    @Transactional
    public PlatformAirtimeSettings loadSingleton() {
        return repository.findById(PlatformAirtimeSettings.SINGLETON_ID).orElseGet(() -> {
            PlatformAirtimeSettings created = new PlatformAirtimeSettings();
            created.setId(PlatformAirtimeSettings.SINGLETON_ID);
            return repository.save(created);
        });
    }

    private void mergeCredentials(PlatformAirtimeSettings row, UpdatePlatformAirtimeSettingsRequest body) {
        if (body.consumerKey() == null && body.consumerSecret() == null) {
            return;
        }
        Map<String, String> merged = decryptMap(row.getCredentialsEnc()).orElseGet(LinkedHashMap::new);
        if (body.consumerKey() != null && !body.consumerKey().isBlank()) {
            merged.put("consumerKey", body.consumerKey().trim());
        }
        if (body.consumerSecret() != null && !body.consumerSecret().isBlank()) {
            merged.put("consumerSecret", body.consumerSecret().trim());
        }
        merged.put("environment", row.getEnvironment());
        row.setCredentialsEnc(encryptionService.encrypt(writeJson(merged)));
    }

    private PlatformAirtimeSettingsResponse toResponse(PlatformAirtimeSettings row) {
        Map<String, String> creds = decryptMap(row.getCredentialsEnc()).orElse(Map.of());
        String key = creds.get("consumerKey");
        String hint;
        if (key == null || key.isBlank()) {
            hint = null;
        } else if (key.length() > 12) {
            hint = key.substring(0, 8) + "…" + key.substring(key.length() - 4);
        } else {
            hint = "••••";
        }
        return new PlatformAirtimeSettingsResponse(
                row.isEnabled(),
                row.getProvider(),
                row.getBaseUrl(),
                row.getEnvironment(),
                row.getCredentialsEnc() != null && !row.getCredentialsEnc().isBlank(),
                hint,
                row.getTenantCommissionPercent(),
                row.getMinAmount(),
                row.getMaxAmount(),
                row.getDailyTenantLimit(),
                row.getCurrency(),
                row.isPosEnabled(),
                row.isStorefrontEnabled(),
                row.getFloatBalance(),
                row.getFloatLowThreshold(),
                row.isFloatLow(),
                row.getFloatCheckedAt(),
                row.getFloatConstrainedUntil(),
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
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not serialize credentials");
        }
    }

    private static String normalizeEnv(String env) {
        return "production".equalsIgnoreCase(env.trim()) ? "production" : "sandbox";
    }
}
