package zelisline.ub.crm.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.crm.domain.CrmOutbound;

public interface CrmOutboundRepository extends JpaRepository<CrmOutbound, String> {

    boolean existsByBusinessIdAndIdempotencyKey(String businessId, String idempotencyKey);

    @Query("""
            select o from CrmOutbound o
            where o.status = 'pending'
            and (o.nextAttemptAt is null or o.nextAttemptAt <= :now)
            order by o.createdAt asc
            """)
    List<CrmOutbound> findDuePending(@Param("now") Instant now, Pageable pageable);
}
