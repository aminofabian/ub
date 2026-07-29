package zelisline.ub.payments.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.SubscribeWebhookTillsResponse;
import zelisline.ub.payments.api.dto.WebhookSubscriptionItemResponse;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.infrastructure.KopokopoPaymentGateway;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Registers KopoKopo till-scoped {@code buygoods_transaction_received} webhook subscriptions
 * so pay-to-till (and STK settlement) notifications hit {@code /webhooks/kopokopo/payment}.
 */
@Service
@RequiredArgsConstructor
public class KopokopoWebhookSubscriptionService {

    public static final String BUYGOODS_EVENT = "buygoods_transaction_received";
    private static final String SCOPE_TILL = "till";

    private final PaymentGatewayConfigRepository configRepository;
    private final CredentialEncryptionService encryptionService;
    private final KopokopoPaymentGateway kopokopoGateway;
    private final ObjectMapper objectMapper;

    @Value("${app.public.api-base-url}")
    private String publicApiBaseUrl;

    public SubscribeWebhookTillsResponse subscribeBuygoodsTills(
            String businessId,
            String configId,
            List<String> requestedTillNumbers
    ) {
        PaymentGatewayConfig cfg = configRepository.findById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Gateway config not found: " + configId));
        if (!cfg.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Gateway config does not belong to this business");
        }
        if (cfg.getGatewayType() != GatewayType.KOPOKOPO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Webhook subscriptions are only supported for KopoKopo");
        }
        if (!cfg.isActive()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Activate the KopoKopo gateway before subscribing till webhooks.");
        }

        Map<String, String> creds = decryptCredentials(cfg);
        List<String> tills = resolveTillNumbers(creds, requestedTillNumbers);
        if (tills.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No till numbers provided. Set tillNumber / webhookTillNumbers on the gateway, or pass tillNumbers.");
        }

        String base = publicApiBaseUrl == null ? "" : publicApiBaseUrl.replaceAll("/$", "");
        if (base.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "API_PUBLIC_BASE_URL is not configured — KopoKopo cannot reach your webhook.");
        }
        if (!base.toLowerCase().startsWith("https://")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "KopoKopo requires an HTTPS webhook URL. Set API_PUBLIC_BASE_URL to a public https origin (not localhost).");
        }
        String webhookUrl = base + "/webhooks/kopokopo/payment";
        List<WebhookSubscriptionItemResponse> results = new ArrayList<>();
        for (String till : tills) {
            var result = kopokopoGateway.createWebhookSubscription(
                    creds, BUYGOODS_EVENT, webhookUrl, SCOPE_TILL, till);
            results.add(new WebhookSubscriptionItemResponse(
                    till,
                    result.success(),
                    result.locationUrl(),
                    result.errorMessage()));
        }
        return new SubscribeWebhookTillsResponse(webhookUrl, BUYGOODS_EVENT, results);
    }

    /**
     * Prefer explicit request tills; otherwise credentials {@code tillNumber} + {@code webhookTillNumbers}.
     */
    static List<String> resolveTillNumbers(Map<String, String> creds, List<String> requested) {
        Set<String> out = new LinkedHashSet<>();
        if (requested != null) {
            for (String raw : requested) {
                addTill(out, raw);
            }
        }
        if (!out.isEmpty()) {
            return List.copyOf(out);
        }
        if (creds != null) {
            addTill(out, creds.getOrDefault("tillNumber", creds.get("shortcode")));
            String extra = creds.get("webhookTillNumbers");
            if (extra != null) {
                for (String part : extra.split("[,\\s]+")) {
                    addTill(out, part);
                }
            }
        }
        return List.copyOf(out);
    }

    private static void addTill(Set<String> out, String raw) {
        if (raw == null) {
            return;
        }
        // Allow accidental "3020127, 3502582" pasted into a single till field.
        for (String part : raw.split("[,\\s]+")) {
            String till = part.trim();
            if (!till.isEmpty() && till.chars().allMatch(Character::isDigit)) {
                out.add(till);
            }
        }
    }

    private Map<String, String> decryptCredentials(PaymentGatewayConfig cfg) {
        try {
            String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
            return objectMapper.readValue(decrypted, new TypeReference<>() {
            });
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Could not read gateway credentials: " + e.getMessage());
        }
    }
}
