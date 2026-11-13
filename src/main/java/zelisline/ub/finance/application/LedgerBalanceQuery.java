package zelisline.ub.finance.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.finance.repository.JournalLineRepository;

/** Read-only balances straight from the journal, for reconciling a subledger against the GL. */
@Service
@RequiredArgsConstructor
public class LedgerBalanceQuery {

    private final JournalLineRepository journalLineRepository;

    /** Credit minus debit on the given account codes (a liability's outstanding balance). */
    @Transactional(readOnly = true)
    public BigDecimal creditBalance(String businessId, Collection<String> accountCodes) {
        BigDecimal balance = journalLineRepository.creditBalance(businessId, accountCodes);
        return (balance == null ? BigDecimal.ZERO : balance).setScale(2, RoundingMode.HALF_UP);
    }
}
