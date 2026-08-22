package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.CreditClaimChannels;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerAccountResolver;
import zelisline.ub.finance.application.LedgerPostingPort;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.sales.SalesConstants;

@Service
@RequiredArgsConstructor
public class CreditsJournalService {

    private static final int MONEY_SCALE = 2;

    private final LedgerPostingPort ledgerPostingPort;
    private final LedgerAccountResolver ledgerAccountResolver;
    private final JournalEntryRepository journalEntryRepository;
    private final JournalLineRepository journalLineRepository;

    @Transactional
    public String postCashWalletTopUp(String businessId, BigDecimal amount, String sourceId, String memo) {
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_WALLET_TOPUP_CASH,
                sourceId,
                memo,
                LedgerAccountCodes.OPERATING_CASH,
                LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY,
                amount
        );
    }

    @Transactional
    public String postMpesaWalletTopUp(String businessId, BigDecimal amount, String sourceId, String memo) {
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_WALLET_TOPUP_MPESA_STK,
                sourceId,
                memo,
                LedgerAccountCodes.MPESA_CLEARING,
                LedgerAccountCodes.CUSTOMER_WALLET_LIABILITY,
                amount
        );
    }

    /** Customer paid money (M-Pesa) toward open AR — clears receivable asset. */
    @Transactional
    public String postInboundMpesaTowardAr(String businessId, BigDecimal amount, String sourceId, String memo) {
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM,
                sourceId,
                memo,
                LedgerAccountCodes.MPESA_CLEARING,
                LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS,
                amount
        );
    }

    /** Customer paid cash at the counter to clear AR — alternate settlement channel for public claims. */
    @Transactional
    public String postInboundCashTowardAr(String businessId, BigDecimal amount, String sourceId, String memo) {
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM,
                sourceId,
                memo,
                LedgerAccountCodes.OPERATING_CASH,
                LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS,
                amount
        );
    }

    /**
     * Loyalty earn accrual: Dr {@code 5310 LOYALTY_MARKETING_EXPENSE} / Cr {@code 2196 LOYALTY_REDEMPTION_LIABILITY}
     * for the KES value of newly issued points (ADR-0009). Skips when amount is non-positive.
     */
    @Transactional
    public String postLoyaltyEarnAccrual(String businessId, BigDecimal amount, String sourceId, String memo) {
        BigDecimal amt = amount == null ? BigDecimal.ZERO : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            return null;
        }
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_LOYALTY_EARN_ACCRUAL,
                sourceId,
                memo,
                LedgerAccountCodes.LOYALTY_MARKETING_EXPENSE,
                LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY,
                amt
        );
    }

    /**
     * Opposite of an inbound AR settlement: Dr AR / Cr cash or M-Pesa clearing.
     */
    @Transactional
    public String postInboundArPaymentReversal(
            String businessId,
            BigDecimal amount,
            String sourceId,
            String memo,
            String channel
    ) {
        boolean cash = channel == null || CreditClaimChannels.CASH.equals(channel);
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM_REVERSAL,
                sourceId,
                memo,
                LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS,
                cash ? LedgerAccountCodes.OPERATING_CASH : LedgerAccountCodes.MPESA_CLEARING,
                amount
        );
    }

    /**
     * Posts the opposite of an existing two-line inbound AR journal. Returns null when the
     * original entry is missing.
     */
    @Transactional
    public String reversePostedInboundAr(
            String businessId,
            String originalJournalId,
            String reversalSourceId,
            String memo
    ) {
        if (originalJournalId == null || originalJournalId.isBlank()) {
            return null;
        }
        JournalEntry original = journalEntryRepository.findById(originalJournalId).orElse(null);
        if (original == null || !businessId.equals(original.getBusinessId())) {
            return null;
        }
        List<JournalLine> lines = journalLineRepository.findByJournalEntryId(original.getId());
        if (lines.isEmpty()) {
            return null;
        }
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(SalesConstants.JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM_REVERSAL);
        entry.setSourceId(reversalSourceId);
        entry.setMemo(memo);
        for (JournalLine line : lines) {
            if (line.getDebit() != null && line.getDebit().signum() > 0) {
                entry.credit(line.getLedgerAccountId(), line.getDebit());
            }
            if (line.getCredit() != null && line.getCredit().signum() > 0) {
                entry.debit(line.getLedgerAccountId(), line.getCredit());
            }
        }
        return ledgerPostingPort.post(entry);
    }

    /** Reverse a previously accrued earn (void or partial refund). Skips when amount is non-positive. */
    @Transactional
    public String postLoyaltyEarnReversal(String businessId, BigDecimal amount, String sourceId, String memo) {
        BigDecimal amt = amount == null ? BigDecimal.ZERO : amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        if (amt.signum() <= 0) {
            return null;
        }
        return postBalancedTwoLine(
                businessId,
                SalesConstants.JOURNAL_SOURCE_LOYALTY_EARN_REVERSAL,
                sourceId,
                memo,
                LedgerAccountCodes.LOYALTY_REDEMPTION_LIABILITY,
                LedgerAccountCodes.LOYALTY_MARKETING_EXPENSE,
                amt
        );
    }

    private String postBalancedTwoLine(
            String businessId,
            String sourceType,
            String sourceId,
            String memo,
            String debitCode,
            String creditCode,
            BigDecimal rawAmount
    ) {
        BigDecimal amt = rawAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        JournalEntry entry = new JournalEntry();
        entry.setBusinessId(businessId);
        entry.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        entry.setSourceType(sourceType);
        entry.setSourceId(sourceId);
        entry.setMemo(memo);
        entry.debit(ledgerAccountResolver.resolveId(businessId, debitCode), amt);
        entry.credit(ledgerAccountResolver.resolveId(businessId, creditCode), amt);
        return ledgerPostingPort.post(entry);
    }
}
