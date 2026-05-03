package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.MpesaStkStatuses;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.MpesaStkIntent;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.MpesaStkIntentRepository;

@Service
@RequiredArgsConstructor
public class MpesaStkIntentService {

    private static final int MONEY_SCALE = 2;

    private final MpesaStkIntentRepository mpesaStkIntentRepository;
    private final CreditAccountRepository creditAccountRepository;
    private final WalletLedgerService walletLedgerService;

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
        row.setCheckoutRequestId("STUB-" + java.util.UUID.randomUUID());
        mpesaStkIntentRepository.save(row);
        return row;
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
