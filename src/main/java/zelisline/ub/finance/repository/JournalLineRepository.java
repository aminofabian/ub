package zelisline.ub.finance.repository;

import java.math.BigDecimal;
import java.util.Collection;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.JournalLine;

public interface JournalLineRepository extends JpaRepository<JournalLine, String> {

    List<JournalLine> findByJournalEntryId(String journalEntryId);

    /** Credit minus debit across every journal line on the given account codes of one business. */
    @Query("""
            select coalesce(sum(l.credit - l.debit), 0)
            from JournalLine l, LedgerAccount a
            where a.id = l.ledgerAccountId
              and a.businessId = :businessId
              and a.code in :codes
            """)
    BigDecimal creditBalance(@Param("businessId") String businessId, @Param("codes") Collection<String> codes);
}
