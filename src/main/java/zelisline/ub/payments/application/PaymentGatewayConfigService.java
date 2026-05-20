package zelisline.ub.payments.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.AvailableGatewayResponse;
import zelisline.ub.payments.api.dto.GatewayConfigRequest;
import zelisline.ub.payments.api.dto.GatewayConfigResponse;
import zelisline.ub.payments.api.dto.GatewayCredentialSettingsResponse;
import zelisline.ub.payments.api.dto.TestConnectionResponse;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Tenant-scoped service for payment gateway configuration CRUD,
 * status lifecycle management, and connection testing.
 */
@Service
@RequiredArgsConstructor
public class PaymentGatewayConfigService {

    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayService platformService;
    private final PaymentGatewayRegistry registry;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    // ── Available gateways ────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<AvailableGatewayResponse> listAvailable(String businessId) {
        List<PlatformPaymentGateway> platformGateways = platformService.listEnabled();
        List<PaymentGatewayConfig> tenantConfigs = configRepository.findByBusinessId(businessId);

        Map<GatewayType, PaymentGatewayConfig> configByType = tenantConfigs.stream()
                .collect(java.util.stream.Collectors.toMap(
                        PaymentGatewayConfig::getGatewayType,
                        c -> c,
                        (a, b) -> a));

        List<AvailableGatewayResponse> result = new ArrayList<>();
        for (PlatformPaymentGateway pg : platformGateways) {
            PaymentGatewayConfig cfg = configByType.get(pg.getGatewayType());
            result.add(new AvailableGatewayResponse(
                    pg.getGatewayType().name(),
                    pg.getDisplayName(),
                    pg.getDescription(),
                    pg.getLogoUrl(),
                    pg.getSortOrder(),
                    cfg != null,
                    cfg != null ? cfg.getId() : null,
                    cfg != null ? cfg.getStatus().name() : null
            ));
        }
        return result;
    }

    // ── CRUD ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<GatewayConfigResponse> listConfigs(String businessId) {
        return configRepository.findByBusinessId(businessId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public GatewayConfigResponse getConfig(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        return toResponse(cfg);
    }

    /**
     * Non-secret credential fields for the admin edit form (till number, environment, etc.).
     */
    @Transactional(readOnly = true)
    public GatewayCredentialSettingsResponse getCredentialSettings(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        if (cfg.getGatewayType() == GatewayType.MANUAL) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Manual payment methods do not use API credentials");
        }
        CredentialReadResult read = tryReadCredentialsMap(cfg);
        if (!read.readable()) {
            return GatewayCredentialSettingsResponse.unreadable(read.errorMessage());
        }
        return toCredentialSettings(read.credentials());
    }

    @Transactional
    public GatewayConfigResponse create(String businessId, GatewayConfigRequest request) {
        GatewayType type = GatewayType.fromWire(request.gatewayType());

        // MANUAL is always available; other types require platform enablement
        if (type != GatewayType.MANUAL) {
            List<PlatformPaymentGateway> enabled = platformService.listEnabled();
            boolean available = enabled.stream()
                    .anyMatch(pg -> pg.getGatewayType() == type);
            if (!available) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Gateway type not available on this platform: " + type.name());
            }
        }

        if (configRepository.existsByBusinessIdAndGatewayType(businessId, type)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A configuration already exists for gateway type: " + type.name());
        }

        PaymentGatewayConfig cfg = new PaymentGatewayConfig();
        cfg.setBusinessId(businessId);
        cfg.setGatewayType(type);
        cfg.setLabel(request.label());
        cfg.setDefault(request.isDefault());
        cfg.setStatus(type == GatewayType.MANUAL ? GatewayStatus.ACTIVE : GatewayStatus.DRAFT);

        if (request.credentialsJson() != null && !request.credentialsJson().isBlank()) {
            cfg.setCredentialsJson(encryptionService.encrypt(request.credentialsJson()));
        }
        if (request.displayInstructionsJson() != null && !request.displayInstructionsJson().isBlank()) {
            cfg.setDisplayInstructionsJson(request.displayInstructionsJson());
        }

        try {
            configRepository.save(cfg);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A configuration already exists for gateway type: " + type.name());
        }
        return toResponse(cfg);
    }

    @Transactional
    public GatewayConfigResponse update(String businessId, String configId, GatewayConfigRequest request) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        GatewayType newType = GatewayType.fromWire(request.gatewayType());

        boolean credentialsChanged = false;

        if (!cfg.getGatewayType().equals(newType)) {
            cfg.setGatewayType(newType);
            credentialsChanged = true;
        }
        cfg.setLabel(request.label());
        cfg.setDefault(request.isDefault());

        if (request.credentialsJson() != null && !request.credentialsJson().isBlank()) {
            String mergedPlaintext = mergeCredentialsPlaintext(
                    cfg.getCredentialsJson(),
                    request.credentialsJson());
            String newEncrypted = encryptionService.encrypt(mergedPlaintext);
            if (!newEncrypted.equals(cfg.getCredentialsJson())) {
                cfg.setCredentialsJson(newEncrypted);
                credentialsChanged = true;
            }
        }
        if (request.displayInstructionsJson() != null) {
            cfg.setDisplayInstructionsJson(request.displayInstructionsJson());
        }

        // Changing credentials or gateway type resets to DRAFT
        if (credentialsChanged) {
            cfg.setStatus(cfg.getGatewayType() == GatewayType.MANUAL ? GatewayStatus.ACTIVE : GatewayStatus.DRAFT);
            cfg.setLastTestedAt(null);
            cfg.setTestErrorJson(null);
        }

        configRepository.save(cfg);
        return toResponse(cfg);
    }

    @Transactional
    public void delete(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        configRepository.delete(cfg);
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    @Transactional
    public TestConnectionResponse testConnection(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);

        if (!cfg.canTest()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway must be in DRAFT or ERROR status to test. Current: " + cfg.getStatus());
        }

        cfg.setStatus(GatewayStatus.TESTING);
        configRepository.save(cfg);

        String encryptedAtRest = cfg.getCredentialsJson();
        String decryptedPlaintext = null;

        try {
            PaymentGateway gw = registry.get(cfg.getGatewayType().name());

            if (encryptedAtRest != null && !encryptedAtRest.isBlank()) {
                decryptedPlaintext = encryptionService.decrypt(encryptedAtRest);
                cfg.setCredentialsJson(decryptedPlaintext);
            }

            ValidationResult result = gw.validateConfiguration(cfg);

            if (decryptedPlaintext != null && !decryptedPlaintext.isBlank()) {
                cfg.setCredentialsJson(encryptionService.encrypt(decryptedPlaintext));
            }

            if (result.valid()) {
                cfg.setStatus(GatewayStatus.TESTED);
                cfg.setLastTestedAt(Instant.now());
                cfg.setTestErrorJson(null);
                configRepository.save(cfg);
                return new TestConnectionResponse(true, GatewayStatus.TESTED.name(), null, null);
            } else {
                cfg.setStatus(GatewayStatus.ERROR);
                cfg.setLastTestedAt(Instant.now());
                cfg.setTestErrorJson(toJson(Map.of(
                        "code", result.errorCode() != null ? result.errorCode() : "UNKNOWN",
                        "message", result.errorMessage() != null ? result.errorMessage() : "Unknown error",
                        "timestamp", Instant.now().toString()
                )));
                configRepository.save(cfg);
                return new TestConnectionResponse(false, GatewayStatus.ERROR.name(),
                        result.errorCode(), result.errorMessage());
            }
        } catch (Exception e) {
            if (encryptedAtRest != null && !encryptedAtRest.isBlank()) {
                cfg.setCredentialsJson(encryptedAtRest);
            }
            cfg.setStatus(GatewayStatus.ERROR);
            cfg.setLastTestedAt(Instant.now());
            cfg.setTestErrorJson(toJson(Map.of(
                    "code", "INTERNAL_ERROR",
                    "message", e.getMessage() != null ? e.getMessage() : "Internal error",
                    "timestamp", Instant.now().toString()
            )));
            configRepository.save(cfg);
            return new TestConnectionResponse(false, GatewayStatus.ERROR.name(),
                    "INTERNAL_ERROR", e.getMessage());
        }
    }

    @Transactional
    public GatewayConfigResponse activate(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        if (cfg.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway is already ACTIVE.");
        }
        // Manual methods can activate from any status (skip testing); API gateways require TESTED
        if (cfg.getGatewayType() != GatewayType.MANUAL && !cfg.canActivate()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway must be in TESTED status to activate. Current: " + cfg.getStatus());
        }
        cfg.setStatus(GatewayStatus.ACTIVE);
        configRepository.save(cfg);
        return toResponse(cfg);
    }

    @Transactional
    public GatewayConfigResponse deactivate(String businessId, String configId) {
        PaymentGatewayConfig cfg = findOwn(businessId, configId);
        if (!cfg.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Gateway must be ACTIVE to deactivate. Current: " + cfg.getStatus());
        }
        cfg.setStatus(GatewayStatus.TESTED);
        configRepository.save(cfg);
        return toResponse(cfg);
    }

    // ── Helpers ───────────────────────────────────────────────────

    private PaymentGatewayConfig findOwn(String businessId, String configId) {
        PaymentGatewayConfig cfg = configRepository.findById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Gateway config not found: " + configId));
        if (!cfg.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Gateway config does not belong to this business");
        }
        return cfg;
    }

    private GatewayConfigResponse toResponse(PaymentGatewayConfig cfg) {
        return new GatewayConfigResponse(
                cfg.getId(),
                cfg.getBusinessId(),
                cfg.getGatewayType().name(),
                cfg.getLabel(),
                cfg.getStatus().name(),
                cfg.isDefault(),
                cfg.getLastTestedAt(),
                cfg.getCreatedAt(),
                cfg.getUpdatedAt()
        );
    }

    private String toJson(Map<String, String> map) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, String> readCredentialsMap(PaymentGatewayConfig cfg) {
        CredentialReadResult read = tryReadCredentialsMap(cfg);
        if (!read.readable()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, read.errorMessage());
        }
        return read.credentials();
    }

    private CredentialReadResult tryReadCredentialsMap(PaymentGatewayConfig cfg) {
        String atRest = cfg.getCredentialsJson();
        if (atRest == null || atRest.isBlank()) {
            return new CredentialReadResult(true, null, Map.of());
        }
        try {
            String decrypted = encryptionService.decrypt(atRest);
            if (decrypted == null || decrypted.isBlank()) {
                return new CredentialReadResult(true, null, Map.of());
            }
            return new CredentialReadResult(true, null, parseCredentialsMap(decrypted));
        } catch (Exception e) {
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return new CredentialReadResult(
                    false,
                    "Stored credentials could not be read (" + detail
                            + "). Re-enter all API keys and till number below, then save.",
                    Map.of());
        }
    }

    private Map<String, String> parseCredentialsMap(String json) throws JsonProcessingException {
        JsonNode node = objectMapper.readTree(json);
        if (!node.isObject()) {
            return Map.of();
        }
        Map<String, String> out = new java.util.LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) {
                return;
            }
            out.put(entry.getKey(), value.isValueNode() ? value.asText() : value.toString());
        });
        return out;
    }

    private String mergeCredentialsPlaintext(String encryptedAtRest, String incomingJson) {
        try {
            Map<String, String> merged = new java.util.LinkedHashMap<>();
            if (encryptedAtRest != null && !encryptedAtRest.isBlank()) {
                CredentialReadResult existing = tryReadCredentialsFromBlob(encryptedAtRest);
                if (existing.readable()) {
                    merged.putAll(existing.credentials());
                }
            }
            Map<String, String> incoming = parseCredentialsMap(incomingJson);
            for (var entry : incoming.entrySet()) {
                if (entry.getValue() != null && !entry.getValue().isBlank()) {
                    merged.put(entry.getKey(), entry.getValue().trim());
                }
            }
            return objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Invalid credentials JSON: " + e.getMessage());
        }
    }

    private static GatewayCredentialSettingsResponse toCredentialSettings(
            Map<String, String> creds
    ) {
        String till = firstNonBlank(creds, "tillNumber", "shortcode");
        String env = creds.get("environment");
        if (env == null || env.isBlank()) {
            env = "sandbox";
        }
        return new GatewayCredentialSettingsResponse(
                env,
                till,
                creds.get("shortcode"),
                creds.get("shortcodeType"),
                isPresent(creds, "clientId"),
                isPresent(creds, "clientSecret"),
                isPresent(creds, "apiKey"),
                isPresent(creds, "secretKey"),
                isPresent(creds, "publicKey"),
                isPresent(creds, "consumerKey"),
                isPresent(creds, "consumerSecret"),
                isPresent(creds, "passkey"),
                true,
                null
        );
    }

    private CredentialReadResult tryReadCredentialsFromBlob(String encryptedAtRest) {
        try {
            String decrypted = encryptionService.decrypt(encryptedAtRest);
            if (decrypted == null || decrypted.isBlank()) {
                return new CredentialReadResult(true, null, Map.of());
            }
            return new CredentialReadResult(true, null, parseCredentialsMap(decrypted));
        } catch (Exception e) {
            return new CredentialReadResult(false, e.getMessage(), Map.of());
        }
    }

    private record CredentialReadResult(
            boolean readable,
            String errorMessage,
            Map<String, String> credentials
    ) {
    }

    private static boolean isPresent(Map<String, String> creds, String key) {
        String v = creds.get(key);
        return v != null && !v.isBlank();
    }

    private static String firstNonBlank(Map<String, String> creds, String... keys) {
        for (String key : keys) {
            String v = creds.get(key);
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
