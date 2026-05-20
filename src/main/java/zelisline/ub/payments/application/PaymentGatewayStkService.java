package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.PlatformPaymentGateway;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Initiates STK Push via the first available ACTIVE online gateway for a business.
 */
@Service
@RequiredArgsConstructor
public class PaymentGatewayStkService {

    private static final Logger log = LoggerFactory.getLogger(PaymentGatewayStkService.class);

    private static final GatewayType[] STK_GATEWAY_PRIORITY = {
            GatewayType.KOPOKOPO,
            GatewayType.DARAJA,
            GatewayType.PAYSTACK,
            GatewayType.PESAPAL,
    };

    private final PaymentGatewayConfigRepository configRepository;
    private final PlatformPaymentGatewayService platformPaymentGatewayService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Value("${app.public.api-base-url:http://localhost:5050}")
    private String publicApiBaseUrl;

    public StkPushOutcome initiate(
            String businessId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        return initiate(businessId, null, phoneNumber, amount, reference, description);
    }

    public StkPushOutcome initiate(
            String businessId,
            String preferredConfigId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        if (preferredConfigId == null || preferredConfigId.isBlank()) {
            preferredConfigId = findDefaultActiveStkConfigId(businessId);
        }

        if (preferredConfigId != null && !preferredConfigId.isBlank()) {
            StkPushOutcome preferred = tryConfig(
                    businessId, preferredConfigId, phoneNumber, amount, reference, description);
            if (preferred != null) {
                return preferred;
            }
        }

        StkPushOutcome lastOutcome = null;
        boolean attempted = false;

        for (GatewayType type : STK_GATEWAY_PRIORITY) {
            if (!isPlatformEnabled(type)) {
                continue;
            }
            var configs = configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE);
            for (PaymentGatewayConfig cfg : configs) {
                StkPushOutcome outcome = pushWithConfig(
                        cfg, phoneNumber, amount, reference, description);
                if (outcome == null) {
                    continue;
                }
                attempted = true;
                lastOutcome = outcome;
                if (outcome.accepted()) {
                    return outcome;
                }
            }
        }

        if (attempted && lastOutcome != null) {
            log.warn("STK declined for business={}: {} {}", businessId,
                    lastOutcome.responseCode(), lastOutcome.message());
            return lastOutcome;
        }

        log.warn("No ACTIVE online STK gateway for business={} (check tenant id, platform enable, Activate)", businessId);
        return StkPushOutcome.rejected(null, "NO_GATEWAY", "Online payment is not available right now.");
    }

    /**
     * First ACTIVE tenant config for an enabled platform STK gateway (same selection as storefront checkout).
     */
    public String findDefaultActiveStkConfigId(String businessId) {
        for (GatewayType type : STK_GATEWAY_PRIORITY) {
            if (!isPlatformEnabled(type)) {
                continue;
            }
            var configs = configRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE);
            if (!configs.isEmpty()) {
                return configs.getFirst().getId();
            }
        }
        return null;
    }

    private boolean isPlatformEnabled(GatewayType type) {
        return platformPaymentGatewayService.listEnabled().stream()
                .map(PlatformPaymentGateway::getGatewayType)
                .anyMatch(t -> t == type);
    }

    private StkPushOutcome tryConfig(
            String businessId,
            String configId,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        PaymentGatewayConfig cfg = configRepository.findById(configId).orElse(null);
        if (cfg == null || !businessId.equals(cfg.getBusinessId())) {
            return null;
        }
        if (cfg.getStatus() != GatewayStatus.ACTIVE || cfg.getGatewayType() == GatewayType.MANUAL) {
            return null;
        }
        return pushWithConfig(cfg, phoneNumber, amount, reference, description);
    }

    private StkPushOutcome pushWithConfig(
            PaymentGatewayConfig cfg,
            String phoneNumber,
            BigDecimal amount,
            String reference,
            String description
    ) {
        GatewayType type = cfg.getGatewayType();
        if (!gatewayRegistry.has(type.name())) {
            return null;
        }
        PaymentGateway gw = gatewayRegistry.get(type.name());
        try {
            String decrypted;
            try {
                decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            } catch (RuntimeException decryptError) {
                String hint = encryptionService.usesEphemeralKey()
                        ? "Server payment encryption key is not configured — contact the store admin."
                        : "Ask the store to re-save KopoKopo credentials in Payments settings (Test connection, then Activate).";
                return StkPushOutcome.rejected(
                        type.name(),
                        "CREDENTIALS",
                        decryptError.getMessage() != null ? decryptError.getMessage() + " " + hint : hint);
            }
            @SuppressWarnings("unchecked")
            Map<String, String> creds = objectMapper.readValue(decrypted, Map.class);

            StkPushRequest pushReq = new StkPushRequest(
                    cfg.getBusinessId(),
                    phoneNumber,
                    amount,
                    reference,
                    description != null ? description : "Order payment",
                    publicApiBaseUrl.replaceAll("/$", ""),
                    creds
            );

            StkPushResponse response = gw.initiateStkPush(pushReq);
            if (response.accepted() && response.gatewayCheckoutRequestId() != null) {
                log.info("STK Push via {} accepted: checkoutId={}", type, response.gatewayCheckoutRequestId());
                return StkPushOutcome.accepted(type.name(), cfg.getId(), response.gatewayCheckoutRequestId(),
                        "Check your phone to complete M-Pesa payment.");
            }
            log.warn("STK Push via {} rejected: {} {}", type, response.responseCode(), response.responseDescription());
            return StkPushOutcome.rejected(type.name(), response.responseCode(),
                    response.responseDescription() != null ? response.responseDescription() : "Payment request declined");
        } catch (Exception e) {
            log.error("STK Push via {} failed for config={}", type, cfg.getId(), e);
            return StkPushOutcome.rejected(type.name(), "ERROR", e.getMessage() != null ? e.getMessage() : "Payment request failed");
        }
    }

    public record StkPushOutcome(
            boolean accepted,
            String gatewayType,
            String configId,
            String checkoutRequestId,
            String responseCode,
            String message
    ) {
        public static StkPushOutcome accepted(
                String gatewayType, String configId, String checkoutRequestId, String message) {
            return new StkPushOutcome(true, gatewayType, configId, checkoutRequestId, "0", message);
        }

        public static StkPushOutcome rejected(String gatewayType, String code, String message) {
            return new StkPushOutcome(false, gatewayType, null, null, code, message);
        }
    }
}
