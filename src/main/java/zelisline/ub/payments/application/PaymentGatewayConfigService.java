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
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.AvailableGatewayResponse;
import zelisline.ub.payments.api.dto.GatewayConfigRequest;
import zelisline.ub.payments.api.dto.GatewayConfigResponse;
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
            String newEncrypted = encryptionService.encrypt(request.credentialsJson());
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

        try {
            PaymentGateway gw = registry.get(cfg.getGatewayType().name());

            // Decrypt credentials for the test call
            if (cfg.getCredentialsJson() != null && !cfg.getCredentialsJson().isBlank()) {
                String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
                // Temporarily set decrypted so validateConfiguration can read it
                cfg.setCredentialsJson(decrypted);
            }

            ValidationResult result = gw.validateConfiguration(cfg);

            // Re-encrypt
            if (cfg.getCredentialsJson() != null && !cfg.getCredentialsJson().isBlank()) {
                cfg.setCredentialsJson(encryptionService.encrypt(cfg.getCredentialsJson()));
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
            // Re-encrypt on error too
            if (cfg.getCredentialsJson() != null && !cfg.getCredentialsJson().isBlank()) {
                try {
                    cfg.setCredentialsJson(encryptionService.encrypt(cfg.getCredentialsJson()));
                } catch (Exception ignored) {
                    // encryption failed — credentials may already be encrypted
                }
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
}
