package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.WalletTxnTypes;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.WalletTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;

@Service
@RequiredArgsConstructor
public class WalletLedgerService {

    private static final int MONEY_SCALE = 2;

    private final CreditAccountRepository creditAccountRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final CreditsJournalService creditsJournalService;

    @Transactional
    public void applyWalletForCompletedSale(
            String businessId,
            String saleId,
            String customerId,
            BigDecimal walletSpend,
            BigDecimal overpayToWallet
    ) {
        BigDecimal spend = n(walletSpend);
        BigDecimal op = n(overpayToWallet);
        if (spend.signum() <= 0 && op.signum() <= 0) {
            return;
        }
        CreditAccount acc = lockAccount(customerId, businessId);
        BigDecimal bal = acc.getWalletBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = bal.subtract(spend).add(op);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient wallet balance");
        }
        acc.setWalletBalance(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        if (spend.signum() > 0) {
            walletTransactionRepository.save(row(businessId, acc.getId(), saleId, WalletTxnTypes.DEBIT_SALE, spend));
        }
        if (op.signum() > 0) {
            walletTransactionRepository.save(row(businessId, acc.getId(), saleId, WalletTxnTypes.CREDIT_OVERPAY_CHANGE, op));
        }
    }

    @Transactional
    public void topUpCashAtCounter(String businessId, String customerId, BigDecimal amount) {
        BigDecimal amt = requirePositive(amount);
        String walletTxnId = UUID.randomUUID().toString();
        creditWallet(businessId, customerId, null, WalletTxnTypes.CREDIT_COUNTER_TOPUP, amt, walletTxnId);
        creditsJournalService.postCashWalletTopUp(businessId, amt, walletTxnId, "Wallet cash top-up");
    }

    @Transactional
    public void creditWalletFromMpesaStk(String businessId, String customerId, BigDecimal amount, String journalSourceId) {
        BigDecimal amt = requirePositive(amount);
        creditWallet(businessId, customerId, null, WalletTxnTypes.CREDIT_MPESA_STK, amt, journalSourceId);
        creditsJournalService.postMpesaWalletTopUp(businessId, amt, journalSourceId, "M-Pesa STK wallet credit");
    }

    @Transactional
    public void reverseWalletEffectsForVoidedSale(String businessId, String saleId, String customerId) {
        if (blank(customerId)) {
            return;
        }
        CreditAccount acc = lockAccount(customerId, businessId);
        List<WalletTransaction> rows = walletTransactionRepository.findBySaleIdOrderByCreatedAtAsc(saleId);
        BigDecimal restoreSpend = BigDecimal.ZERO;
        BigDecimal removeOverpay = BigDecimal.ZERO;
        for (WalletTransaction r : rows) {
            if (!acc.getId().equals(r.getCreditAccountId())) {
                continue;
            }
            if (WalletTxnTypes.DEBIT_SALE.equals(r.getTxnType())) {
                restoreSpend = restoreSpend.add(r.getAmount());
            }
            if (WalletTxnTypes.CREDIT_OVERPAY_CHANGE.equals(r.getTxnType())) {
                removeOverpay = removeOverpay.add(r.getAmount());
            }
        }
        restoreSpend = restoreSpend.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        removeOverpay = removeOverpay.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (restoreSpend.signum() <= 0 && removeOverpay.signum() <= 0) {
            return;
        }
        BigDecimal bal = acc.getWalletBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = bal.add(restoreSpend).subtract(removeOverpay);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Void leaves wallet balance negative");
        }
        acc.setWalletBalance(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        if (restoreSpend.signum() > 0) {
            walletTransactionRepository.save(
                    row(businessId, acc.getId(), saleId, WalletTxnTypes.REVERSAL_VOID_SPEND_RESTORE, restoreSpend));
        }
        if (removeOverpay.signum() > 0) {
            walletTransactionRepository.save(
                    row(businessId, acc.getId(), saleId, WalletTxnTypes.REVERSAL_VOID_OVERPAY_CLAW, removeOverpay));
        }
    }

    @Transactional
    public void refundToWallet(String businessId, String saleId, String customerId, BigDecimal amount) {
        BigDecimal amt = n(amount);
        if (amt.signum() <= 0 || blank(customerId)) {
            return;
        }
        creditWallet(businessId, customerId, saleId, WalletTxnTypes.CREDIT_REFUND, amt, null);
    }

    private void creditWallet(
            String businessId,
            String customerId,
            String saleId,
            String type,
            BigDecimal creditAmount,
            String preferredTxnId
    ) {
        CreditAccount acc = lockAccount(customerId, businessId);
        BigDecimal amt = creditAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        acc.setWalletBalance(acc.getWalletBalance().add(amt).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        WalletTransaction w = row(businessId, acc.getId(), saleId, type, amt);
        if (!blank(preferredTxnId)) {
            w.setId(preferredTxnId);
        }
        walletTransactionRepository.save(w);
    }

    private CreditAccount lockAccount(String customerId, String businessId) {
        return creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Credit account not found"));
    }

    private static WalletTransaction row(
            String businessId,
            String creditAccountId,
            String saleId,
            String type,
            BigDecimal positiveAmountOrSignedReversalRemoval
    ) {
        WalletTransaction row = new WalletTransaction();
        row.setBusinessId(businessId);
        row.setCreditAccountId(creditAccountId);
        row.setSaleId(saleId);
        row.setTxnType(type);
        row.setAmount(positiveAmountOrSignedReversalRemoval.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return row;
    }

    private BigDecimal requirePositive(BigDecimal amt) {
        BigDecimal v = n(amt);
        if (v.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Amount must be positive");
        }
        return v;
    }

    private static BigDecimal n(BigDecimal raw) {
        if (raw == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return raw.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
