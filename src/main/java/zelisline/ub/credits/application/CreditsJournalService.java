package zelisline.ub.credits.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.LedgerAccountCodes;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.finance.domain.JournalEntry;
import zelisline.ub.finance.domain.JournalLine;
import zelisline.ub.finance.domain.LedgerAccount;
import zelisline.ub.finance.repository.JournalEntryRepository;
import zelisline.ub.finance.repository.JournalLineRepository;
import zelisline.ub.finance.repository.LedgerAccountRepository;
import zelisline.ub.sales.SalesConstants;

@Service
@RequiredArgsConstructor
public class CreditsJournalService {

    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");
    private static final int MONEY_SCALE = 2;

    private final LedgerBootstrapService ledgerBootstrapService;
    private final LedgerAccountRepository ledgerAccountRepository;
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
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        BigDecimal amt = amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        LedgerAccount mpesa = ledger(businessId, LedgerAccountCodes.MPESA_CLEARING);
        LedgerAccount ar = ledger(businessId, LedgerAccountCodes.ACCOUNTS_RECEIVABLE_CUSTOMERS);

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(SalesConstants.JOURNAL_SOURCE_PUBLIC_PAYMENT_CLAIM);
        je.setSourceId(sourceId);
        je.setMemo(memo);
        journalEntryRepository.save(je);

        List<JournalLine> lines = List.of(journalDebit(je.getId(), mpesa.getId(), amt),
                journalCredit(je.getId(), ar.getId(), amt));
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
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
        ledgerBootstrapService.ensureStandardAccounts(businessId);
        BigDecimal amt = rawAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        LedgerAccount dr = ledger(businessId, debitCode);
        LedgerAccount cr = ledger(businessId, creditCode);

        JournalEntry je = new JournalEntry();
        je.setBusinessId(businessId);
        je.setEntryDate(LocalDate.now(ZoneOffset.UTC));
        je.setSourceType(sourceType);
        je.setSourceId(sourceId);
        je.setMemo(memo);
        journalEntryRepository.save(je);

        List<JournalLine> lines = new ArrayList<>();
        lines.add(journalDebit(je.getId(), dr.getId(), amt));
        lines.add(journalCredit(je.getId(), cr.getId(), amt));
        journalLineRepository.saveAll(lines);
        assertBalanced(lines);
        return je.getId();
    }

    private LedgerAccount ledger(String businessId, String code) {
        return ledgerAccountRepository.findByBusinessIdAndCode(businessId, code)
                .orElseThrow(() -> new IllegalStateException("Missing ledger account " + code));
    }

    private static void assertBalanced(List<JournalLine> lines) {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (JournalLine l : lines) {
            dr = dr.add(l.getDebit());
            cr = cr.add(l.getCredit());
        }
        if (dr.subtract(cr).abs().compareTo(TOLERANCE) > 0) {
            throw new IllegalStateException("Unbalanced journal");
        }
    }

    private static JournalLine journalDebit(String entryId, String accountId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accountId);
        l.setDebit(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        l.setCredit(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return l;
    }

    private static JournalLine journalCredit(String entryId, String accountId, BigDecimal amount) {
        JournalLine l = new JournalLine();
        l.setJournalEntryId(entryId);
        l.setLedgerAccountId(accountId);
        l.setDebit(BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        l.setCredit(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
        return l;
    }
}
