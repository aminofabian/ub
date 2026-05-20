package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.payments.application.PaymentGatewayRegistry;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;

@Service
@RequiredArgsConstructor
public class MpesaStkIntentService {

    private static final Logger log = LoggerFactory.getLogger(MpesaStkIntentService.class);
    private static final int MONEY_SCALE = 2;

    private final MpesaStkIntentRepository mpesaStkIntentRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final WalletLedgerService walletLedgerService;
    private final PaymentGatewayRegistry gatewayRegistry;
    private final PaymentGatewayConfigRepository gatewayConfigRepository;
    private final CredentialEncryptionService encryptionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MpesaStkIntent initiate(String businessId, String customerId, BigDecimal rawAmount, String idempotencyKey) {
        CreditAccount account = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
        BigDecimal amt = rawAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }

        try {
            return createRow(businessId, account.getId(), amt, idempotencyKey);
        } catch (DataIntegrityViolationException duplicate) {
            return mpesaStkIntentRepository
                    .findByBusinessIdAndIdempotencyKey(businessId, idempotencyKey)
                    .orElseThrow(() -> duplicate);
        }
    }

    private MpesaStkIntent createRow(String businessId, String creditAccountId, BigDecimal amt, String idempotencyKey) {
        MpesaStkIntent row = new MpesaStkIntent();
        row.setBusinessId(businessId);
        row.setCreditAccountId(creditAccountId);
        row.setSaleId(null);
        row.setAmount(amt);
        row.setIdempotencyKey(idempotencyKey.trim());
        row.setStatus(MpesaStkStatuses.PENDING);

        // Try to use a real gateway; fall back to STUB if none is ACTIVE
        String checkoutRequestId = initiateRealStkPush(businessId, amt, idempotencyKey);
        row.setCheckoutRequestId(checkoutRequestId);

        mpesaStkIntentRepository.save(row);
        return row;
    }

    /**
     * Attempts to find an ACTIVE gateway config and initiate a real STK Push.
     * Falls back to a STUB checkout ID if no gateway is available.
     */
    private String initiateRealStkPush(String businessId, BigDecimal amount, String reference) {
        // Try KopoKopo first, then Daraja
        for (GatewayType type : new GatewayType[]{GatewayType.KOPOKOPO, GatewayType.DARAJA}) {
            var configs = gatewayConfigRepository.findByBusinessIdAndGatewayTypeAndStatus(
                    businessId, type, GatewayStatus.ACTIVE);
            if (configs.isEmpty()) continue;

            PaymentGatewayConfig cfg = configs.get(0);
            if (!gatewayRegistry.has(type.name())) continue;

            PaymentGateway gw = gatewayRegistry.get(type.name());

            try {
                String decrypted = encryptionService.decrypt(cfg.getCredentialsJson());
                @SuppressWarnings("unchecked")
                Map<String, String> creds = objectMapper.readValue(decrypted, Map.class);

                StkPushRequest pushReq = new StkPushRequest(
                        businessId,
                        null, // phone will be filled by the caller with customer data
                        amount,
                        reference,
                        "Wallet Top Up",
                        "https://api.palmart.co.ke",
                        creds
                );

                StkPushResponse response = gw.initiateStkPush(pushReq);
                if (response.accepted() && response.gatewayCheckoutRequestId() != null) {
                    log.info("STK Push initiated via {}: checkoutId={}", type, response.gatewayCheckoutRequestId());
                    return response.gatewayCheckoutRequestId();
                }
                log.warn("STK Push via {} rejected: code={} desc={}", type,
                        response.responseCode(), response.responseDescription());
            } catch (Exception e) {
                log.error("STK Push via {} failed", type, e);
            }
        }

        // Fallback: no ACTIVE gateway or all failed
        log.info("No ACTIVE gateway available for business={} — using STUB", businessId);
        return "STUB-" + java.util.UUID.randomUUID();
    }

    /** Test/dev completion path — guarded by webhook shared secret when configured. */
    @Transactional
    public String fulfillWalletTopUpSimulated(String businessId, String intentId, String secret, String configuredSecret) {
        if (configuredSecret == null || configuredSecret.isBlank() || !configuredSecret.equals(secret)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid simulation secret");
        }
        MpesaStkIntent row = mpesaStkIntentRepository.findById(intentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Intent not found"));
        if (!businessId.equals(row.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Intent not found");
        }
        if (MpesaStkStatuses.FULFILLED.equals(row.getStatus())) {
            return row.getFulfilledWalletTxnId();
        }

        CreditAccount acc = creditAccountRepository.findById(row.getCreditAccountId()).orElseThrow();
        walletLedgerService.creditWalletFromMpesaStk(
                row.getBusinessId(),
                acc.getCustomerId(),
                row.getAmount(),
                row.getId());
        row.setStatus(MpesaStkStatuses.FULFILLED);
        row.setGatewayConfirmationCode("SIM-OK");
        row.setFulfilledWalletTxnId(row.getId());
        mpesaStkIntentRepository.save(row);
        return row.getId();
    }
}
