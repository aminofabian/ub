package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;
import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.application.PaymentGatewayStkService;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.StkPushContextType;

@Service
@RequiredArgsConstructor
public class MpesaStkIntentService {

    private static final Logger log = LoggerFactory.getLogger(MpesaStkIntentService.class);
    private static final int MONEY_SCALE = 2;

    private final MpesaStkIntentRepository mpesaStkIntentRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final WalletLedgerService walletLedgerService;
    private final PaymentGatewayStkService paymentGatewayStkService;
    private final GatewayStkPushService gatewayStkPushService;
    private final CustomerPhoneRepository customerPhoneRepository;

    @Transactional
    public MpesaStkIntent initiate(String businessId, String customerId, BigDecimal rawAmount, String idempotencyKey) {
        CreditAccount account = creditAccountRepository.findByCustomerIdAndBusinessId(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
        BigDecimal amt = rawAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }

        String phone = resolveCustomerPhone(customerId);
        try {
            MpesaStkIntent row = createRow(businessId, account.getId(), amt, idempotencyKey, phone);
            return row;
        } catch (DataIntegrityViolationException duplicate) {
            return mpesaStkIntentRepository
                    .findByBusinessIdAndIdempotencyKey(businessId, idempotencyKey)
                    .orElseThrow(() -> duplicate);
        }
    }

    private MpesaStkIntent createRow(
            String businessId,
            String creditAccountId,
            BigDecimal amt,
            String idempotencyKey,
            String phone
    ) {
        MpesaStkIntent row = new MpesaStkIntent();
        row.setBusinessId(businessId);
        row.setCreditAccountId(creditAccountId);
        row.setSaleId(null);
        row.setAmount(amt);
        row.setIdempotencyKey(idempotencyKey.trim());
        row.setStatus(MpesaStkStatuses.PENDING);

        PaymentGatewayStkService.StkPushOutcome outcome = initiateRealStkPush(
                businessId, phone, amt, idempotencyKey);
        String checkoutRequestId = outcome.accepted() && outcome.checkoutRequestId() != null
                ? outcome.checkoutRequestId()
                : "STUB-" + java.util.UUID.randomUUID();
        row.setCheckoutRequestId(checkoutRequestId);

        mpesaStkIntentRepository.save(row);
        registerWalletPush(businessId, row, phone, outcome);
        return row;
    }

    private PaymentGatewayStkService.StkPushOutcome initiateRealStkPush(
            String businessId,
            String phone,
            BigDecimal amount,
            String reference
    ) {
        if (phone == null || phone.isBlank()) {
            log.warn("Wallet STK skipped — no customer phone for business={}", businessId);
            return PaymentGatewayStkService.StkPushOutcome.rejected(null, "NO_PHONE", "Customer has no phone");
        }
        PaymentGatewayStkService.StkPushOutcome outcome = paymentGatewayStkService.initiate(
                businessId,
                null,
                phone,
                amount,
                reference,
                "Wallet Top Up"
        );
        if (!outcome.accepted()) {
            log.info("No ACTIVE gateway accepted wallet STK for business={}", businessId);
        }
        return outcome;
    }

    private void registerWalletPush(
            String businessId,
            MpesaStkIntent intent,
            String phone,
            PaymentGatewayStkService.StkPushOutcome outcome
    ) {
        if (!outcome.accepted()
                || intent.getCheckoutRequestId() == null
                || intent.getCheckoutRequestId().startsWith("STUB-")) {
            return;
        }
        GatewayType gatewayType = GatewayType.valueOf(outcome.gatewayType());
        gatewayStkPushService.registerPush(
                businessId,
                gatewayType,
                outcome.configId(),
                intent.getCheckoutRequestId(),
                intent.getIdempotencyKey(),
                StkPushContextType.WALLET_INTENT,
                intent.getId(),
                intent.getAmount(),
                phone);
    }

    private String resolveCustomerPhone(String customerId) {
        java.util.List<CustomerPhone> phones = customerPhoneRepository.findByCustomerIdOrderByCreatedAtAsc(customerId);
        if (phones.isEmpty()) {
            return null;
        }
        for (CustomerPhone p : phones) {
            if (p.isPrimary() && p.getPhone() != null && !p.getPhone().isBlank()) {
                return p.getPhone().trim();
            }
        }
        return phones.getFirst().getPhone() != null ? phones.getFirst().getPhone().trim() : null;
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
