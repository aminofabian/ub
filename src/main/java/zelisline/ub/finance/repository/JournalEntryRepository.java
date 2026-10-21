package zelisline.ub.finance.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.finance.domain.JournalEntry;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, String> {

    Optional<JournalEntry> findByBusinessIdAndSourceTypeAndSourceId(
            String businessId,
            String sourceType,
            String sourceId
    );

    /** Prefer when source keys may not be unique (partial/double posts). Oldest first. */
    List<JournalEntry> findAllByBusinessIdAndSourceTypeAndSourceIdOrderByCreatedAtAscIdAsc(
            String businessId,
            String sourceType,
            String sourceId
    );

    boolean existsByBusinessIdAndSourceTypeAndSourceId(
            String businessId,
            String sourceType,
            String sourceId
    );

    /**
     * Batch-clearance journals that debited shrinkage ({@code 5210}) for equity-funded
     * supply batches (opening / stock-count gain), with no correction journal yet.
     */
    interface EquityClearanceMispost {
        String getJournalEntryId();
        String getBusinessId();
        String getBranchId();
        LocalDate getEntryDate();
        String getSupplyBatchId();
        String getBatchNumber();
        String getBatchSourceType();
        BigDecimal getShrinkageDebit();
    }

    @Query(value = """
            select je.id              as journalEntryId,
                   je.business_id     as businessId,
                   je.branch_id       as branchId,
                   je.entry_date      as entryDate,
                   sb.id              as supplyBatchId,
                   sb.batch_number    as batchNumber,
                   sb.source_type     as batchSourceType,
                   coalesce(sum(jl.debit), 0) as shrinkageDebit
              from journal_entries je
              join supply_batches sb
                on sb.id = je.source_id
               and sb.business_id = je.business_id
              join journal_lines jl
                on jl.journal_entry_id = je.id
              join ledger_accounts la
                on la.id = jl.ledger_account_id
             where je.source_type = :clearanceSource
               and (sb.source_type = :openingSource or sb.source_type = :stockGainSource)
               and la.code = :shrinkageCode
               and jl.debit > 0
               and (:businessId is null or je.business_id = :businessId)
               and not exists (
                     select 1
                       from journal_entries corr
                      where corr.business_id = je.business_id
                        and corr.source_type = :correctionSource
                        and corr.source_id = je.id
                   )
             group by je.id, je.business_id, je.branch_id, je.entry_date,
                      sb.id, sb.batch_number, sb.source_type
             having coalesce(sum(jl.debit), 0) > 0
             order by je.entry_date, je.id
            """, nativeQuery = true)
    List<EquityClearanceMispost> findUncorrectedEquityClearanceShrinkage(
            @Param("businessId") String businessId,
            @Param("clearanceSource") String clearanceSource,
            @Param("correctionSource") String correctionSource,
            @Param("openingSource") String openingSource,
            @Param("stockGainSource") String stockGainSource,
            @Param("shrinkageCode") String shrinkageCode
    );
}
