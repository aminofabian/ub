package zelisline.ub.messaging.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.messaging.domain.SmsCreditLedgerEntry;

public interface SmsCreditLedgerRepository extends JpaRepository<SmsCreditLedgerEntry, String> {

    List<SmsCreditLedgerEntry> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    /** {@code [kind, sum(delta)]} for spend rows since {@code since} — platform usage dashboard. */
    @Query("""
            select e.kind, coalesce(sum(e.delta), 0)
            from SmsCreditLedgerEntry e
            where e.kind in (
                zelisline.ub.messaging.domain.SmsCreditLedgerKind.INCLUDED_SPEND,
                zelisline.ub.messaging.domain.SmsCreditLedgerKind.PURCHASED_SPEND)
              and e.createdAt >= :since
            group by e.kind
            """)
    List<Object[]> sumSpendByKind(@Param("since") Instant since);

    /** {@code [businessId, sum(-delta)]} — top tenants by SMS spend this cycle. */
    @Query("""
            select e.businessId, coalesce(sum(-e.delta), 0)
            from SmsCreditLedgerEntry e
            where e.kind in (
                zelisline.ub.messaging.domain.SmsCreditLedgerKind.INCLUDED_SPEND,
                zelisline.ub.messaging.domain.SmsCreditLedgerKind.PURCHASED_SPEND)
              and e.createdAt >= :since
            group by e.businessId
            order by 2 desc
            """)
    List<Object[]> topSpenders(@Param("since") Instant since, Pageable pageable);
}
