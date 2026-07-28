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
import zelisline.ub.credits.CreditTxnTypes;
import zelisline.ub.credits.WalletTxnTypes;
import zelisline.ub.credits.domain.CreditAccount;
import zelisline.ub.credits.domain.CreditTransaction;
import zelisline.ub.credits.domain.WalletTransaction;
import zelisline.ub.credits.repository.CreditAccountRepository;
import zelisline.ub.credits.repository.CreditTransactionRepository;
import zelisline.ub.credits.repository.WalletTransactionRepository;

@Service
@RequiredArgsConstructor
public class WalletLedgerService {

    private static final int MONEY_SCALE = 2;

    private final CreditAccountRepository creditAccountRepository;
    private final CreditTransactionRepository creditTransactionRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final CreditsJournalService creditsJournalService;

    /**
     * How cash overpay was split between tab pay-down and wallet credit.
     */
    public record OverpayAllocation(BigDecimal towardAr, BigDecimal towardWallet) {
        public static OverpayAllocation none() {
            return new OverpayAllocation(BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2));
        }
    }

    @Transactional
    public OverpayAllocation applyWalletForCompletedSale(
            String businessId,
            String saleId,
            String customerId,
            BigDecimal walletSpend,
            BigDecimal overpayToWallet
    ) {
        BigDecimal spend = n(walletSpend);
        BigDecimal op = n(overpayToWallet);
        if (spend.signum() <= 0 && op.signum() <= 0) {
            return OverpayAllocation.none();
        }
        if (blank(customerId)) {
            if (spend.signum() > 0 || op.signum() > 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for wallet");
            }
            return OverpayAllocation.none();
        }

        CreditAccount acc = lockOrCreateAccount(customerId, businessId);
        BigDecimal bal = acc.getWalletBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        BigDecimal towardAr = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal towardWallet = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (op.signum() > 0) {
            BigDecimal owed = acc.getBalanceOwed().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            if (owed.signum() > 0) {
                towardAr = op.min(owed);
                BigDecimal nextOwed = owed.subtract(towardAr).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                acc.setBalanceOwed(nextOwed);
                insertCreditPayment(businessId, acc.getId(), saleId, towardAr);
            }
            towardWallet = op.subtract(towardAr).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }

        BigDecimal next = bal.subtract(spend).add(towardWallet);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Insufficient wallet balance");
        }
        acc.setWalletBalance(next);
        acc.setLastActivityAt(Instant.now());
        creditAccountRepository.save(acc);
        if (spend.signum() > 0) {
            walletTransactionRepository.save(row(businessId, acc.getId(), saleId, WalletTxnTypes.DEBIT_SALE, spend));
        }
        if (towardWallet.signum() > 0) {
            walletTransactionRepository.save(
                    row(businessId, acc.getId(), saleId, WalletTxnTypes.CREDIT_OVERPAY_CHANGE, towardWallet));
        }
        return new OverpayAllocation(towardAr, towardWallet);
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
        CreditAccount acc = lockOrCreateAccount(customerId, businessId);
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

        BigDecimal restoreAr = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<CreditTransaction> creditRows =
                creditTransactionRepository.findBySaleIdOrderByCreatedAtAsc(saleId);
        for (CreditTransaction r : creditRows) {
            if (!acc.getId().equals(r.getCreditAccountId())) {
                continue;
            }
            if (CreditTxnTypes.PAYMENT.equals(r.getTxnType())) {
                restoreAr = restoreAr.add(r.getAmount());
            }
        }
        restoreAr = restoreAr.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        if (restoreSpend.signum() <= 0 && removeOverpay.signum() <= 0 && restoreAr.signum() <= 0) {
            return;
        }
        BigDecimal bal = acc.getWalletBalance().setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        BigDecimal next = bal.add(restoreSpend).subtract(removeOverpay);
        if (next.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Void leaves wallet balance negative");
        }
        acc.setWalletBalance(next);
        if (restoreAr.signum() > 0) {
            acc.setBalanceOwed(
                    acc.getBalanceOwed().add(restoreAr).setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            insertCreditTxn(businessId, acc.getId(), saleId, CreditTxnTypes.ADJUSTMENT, restoreAr);
        }
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
        if (amt.signum() <= 0) {
            return;
        }
        if (blank(customerId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Customer required for wallet refund");
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
        CreditAccount acc = lockOrCreateAccount(customerId, businessId);
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

    private CreditAccount lockOrCreateAccount(String customerId, String businessId) {
        return creditAccountRepository.findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                .orElseGet(() -> {
                    CreditAccount row = new CreditAccount();
                    row.setBusinessId(businessId);
                    row.setCustomerId(customerId);
                    row.setCreditLimit(null);
                    creditAccountRepository.saveAndFlush(row);
                    return creditAccountRepository
                            .findByCustomerIdAndBusinessIdForUpdate(customerId, businessId)
                            .orElse(row);
                });
    }

    private void insertCreditPayment(String businessId, String creditAccountId, String saleId, BigDecimal amount) {
        insertCreditTxn(businessId, creditAccountId, saleId, CreditTxnTypes.PAYMENT, amount);
    }

    private void insertCreditTxn(
            String businessId,
            String creditAccountId,
            String saleId,
            String type,
            BigDecimal amount
    ) {
        CreditTransaction txn = new CreditTransaction();
        txn.setBusinessId(businessId);
        txn.setCreditAccountId(creditAccountId);
        txn.setSaleId(saleId);
        txn.setTxnType(type);
        txn.setAmount(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        creditTransactionRepository.save(txn);
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
