package zelisline.ub.payments.api;

import java.util.ArrayList;
import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Public/tenant endpoint that returns display-only payment instructions
 * for rendering on the storefront and cashier POS.
 *
 * <p>Collects all {@code ACTIVE} manual gateway configs for the business
 * and parses their {@code displayInstructionsJson} into
 * {@link DisplayInstructions} records.
 */
@Validated
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentDisplayController {

    private final PaymentGatewayConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/display-instructions")
    public List<DisplayInstructions> getDisplayInstructions(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        List<PaymentGatewayConfig> configs = configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(
                        businessId, GatewayType.MANUAL, GatewayStatus.ACTIVE);

        List<DisplayInstructions> result = new ArrayList<>();
        for (PaymentGatewayConfig cfg : configs) {
            DisplayInstructions di = parseDisplayInstructions(cfg);
            if (di != null) {
                result.add(di);
            }
        }
        return result;
    }

    private DisplayInstructions parseDisplayInstructions(PaymentGatewayConfig cfg) {
        String json = cfg.getDisplayInstructionsJson();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var node = objectMapper.readTree(json);
            return new DisplayInstructions(
                    cfg.getId(),
                    node.has("type") ? node.get("type").asText() : null,
                    node.has("label") ? node.get("label").asText() : cfg.getLabel(),
                    node.has("instructions") ? node.get("instructions").asText() : null,
                    node.has("tillNumber") ? node.get("tillNumber").asText() : null,
                    node.has("businessNumber") ? node.get("businessNumber").asText() : null,
                    node.has("accountNumber") ? node.get("accountNumber").asText() : null,
                    node.has("bankName") ? node.get("bankName").asText() : null,
                    node.has("branchName") ? node.get("branchName").asText() : null,
                    node.has("accountName") ? node.get("accountName").asText() : null,
                    node.has("swiftCode") ? node.get("swiftCode").asText() : null
            );
        } catch (JsonProcessingException e) {
            return null;
        }
    }
}
