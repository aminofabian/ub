package zelisline.ub.payments.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.CustodyReceiveTestRequest;
import zelisline.ub.payments.api.dto.CustodyReceiveTestResponse;
import zelisline.ub.payments.api.dto.GatewayConfigRequest;
import zelisline.ub.payments.api.dto.GatewayConfigResponse;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.StkPushContextType;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

/**
 * Onboarding / settings path: upsert CUSTODY_MPESA destination and fire a KES 1
 * STK so the merchant can confirm cash landed on their till, paybill, or bank.
 */
@Service
@RequiredArgsConstructor
public class CustodyReceiveTestService {

    private static final Logger log = LoggerFactory.getLogger(CustodyReceiveTestService.class);
    private static final BigDecimal DEFAULT_AMOUNT = BigDecimal.ONE;

    private final PaymentGatewayConfigService configService;
    private final PaymentGatewayConfigRepository configRepository;
    private final StkPushRetryHelper stkPushRetryHelper;
    private final GatewayStkPushService gatewayStkPushService;
    private final PlatformCustodySettlementService custodySettlementService;
    private final ObjectMapper objectMapper;

    @Transactional
    public CustodyReceiveTestResponse run(String businessId, CustodyReceiveTestRequest body) {
        String phone = body.phoneNumber() != null ? body.phoneNumber().trim() : "";
        if (phone.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "phoneNumber is required");
        }

        String displayJson = buildDisplayJson(body);
        String destErr = custodySettlementService.validateDestinationJson(displayJson);
        if (destErr != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, destErr);
        }

        String label = body.label() != null && !body.label().isBlank()
                ? body.label().trim()
                : defaultLabel(body);

        GatewayConfigResponse config = upsertCustody(businessId, label, displayJson);

        BigDecimal amount = body.amount() != null && body.amount().signum() > 0
                ? body.amount().setScale(0, RoundingMode.HALF_UP)
                : DEFAULT_AMOUNT;
        String reference = "recv-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String description = "Kiosk receive test";

        PaymentGatewayStkService.StkPushOutcome outcome = stkPushRetryHelper.initiateAfterClearingPhone(
                businessId,
                config.id(),
                phone,
                amount,
                reference,
                description);

        if (outcome.accepted() && outcome.checkoutRequestId() != null) {
            gatewayStkPushService.registerPush(
                    businessId,
                    GatewayType.valueOf(outcome.gatewayType()),
                    outcome.configId(),
                    outcome.checkoutRequestId(),
                    reference,
                    StkPushContextType.ONBOARDING_RECEIVE_TEST,
                    null,
                    amount,
                    phone);
            log.info("Receive-test STK accepted business={} config={} checkoutId={} dest={}",
                    businessId, config.id(), outcome.checkoutRequestId(), summary(body));
            return new CustodyReceiveTestResponse(
                    true,
                    config.id(),
                    outcome.checkoutRequestId(),
                    "Check your phone — enter your M-Pesa PIN, then confirm the cash landed.",
                    amount,
                    phone,
                    summary(body));
        }

        String msg = outcome.message() != null ? outcome.message() : "Could not send the STK prompt.";
        log.warn("Receive-test STK rejected business={} code={} msg={}",
                businessId, outcome.responseCode(), msg);
        return new CustodyReceiveTestResponse(
                false,
                config.id(),
                null,
                msg,
                amount,
                phone,
                summary(body));
    }

    private GatewayConfigResponse upsertCustody(String businessId, String label, String displayJson) {
        PaymentGatewayConfig existing = configRepository
                .findByBusinessIdAndGatewayType(businessId, GatewayType.CUSTODY_MPESA)
                .orElse(null);
        if (existing == null) {
            return configService.create(businessId, new GatewayConfigRequest(
                    GatewayType.CUSTODY_MPESA.name(),
                    label,
                    true,
                    null,
                    displayJson));
        }
        existing.setLabel(label);
        existing.setDisplayInstructionsJson(displayJson);
        existing.setDefault(true);
        if (existing.getStatus() != GatewayStatus.ACTIVE) {
            existing.setStatus(GatewayStatus.ACTIVE);
        }
        configRepository.save(existing);
        return configService.getConfig(businessId, existing.getId());
    }

    private String buildDisplayJson(CustodyReceiveTestRequest body) {
        try {
            Map<String, String> display = new LinkedHashMap<>();
            String type = body.type().trim().toLowerCase();
            display.put("type", type);
            if ("till".equals(type)) {
                display.put("tillNumber", digits(body.tillNumber()));
            } else {
                display.put("businessNumber", digits(body.businessNumber()));
                display.put("accountNumber", body.accountNumber() != null
                        ? body.accountNumber().trim() : "");
            }
            if (body.label() != null && !body.label().isBlank()) {
                display.put("label", body.label().trim());
            }
            return objectMapper.writeValueAsString(display);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid destination");
        }
    }

    private static String defaultLabel(CustodyReceiveTestRequest body) {
        if ("till".equalsIgnoreCase(body.type())) {
            return "M-Pesa Till " + digits(body.tillNumber());
        }
        return "Paybill " + digits(body.businessNumber());
    }

    private static String summary(CustodyReceiveTestRequest body) {
        if ("till".equalsIgnoreCase(body.type())) {
            return "Till " + digits(body.tillNumber());
        }
        return "Paybill " + digits(body.businessNumber())
                + " · Acc " + (body.accountNumber() != null ? body.accountNumber().trim() : "");
    }

    private static String digits(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replaceAll("\\D", "");
    }
}
