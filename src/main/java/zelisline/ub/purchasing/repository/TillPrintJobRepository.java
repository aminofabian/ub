package zelisline.ub.purchasing.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.TillPrintJob;

public interface TillPrintJobRepository extends JpaRepository<TillPrintJob, String> {

    @Query("""
            select j from TillPrintJob j
             where j.businessId = :businessId
               and j.targetUserId = :userId
               and j.claimedAt is null
               and j.createdAt >= :cutoff
             order by j.createdAt asc
            """)
    List<TillPrintJob> findPending(
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("cutoff") Instant cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TillPrintJob j
               set j.claimedAt = :now
             where j.id = :id
               and j.businessId = :businessId
               and j.targetUserId = :userId
               and j.claimedAt is null
               and j.createdAt >= :cutoff
            """)
    int claimIfOpen(
            @Param("id") String id,
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("now") Instant now,
            @Param("cutoff") Instant cutoff);
}
