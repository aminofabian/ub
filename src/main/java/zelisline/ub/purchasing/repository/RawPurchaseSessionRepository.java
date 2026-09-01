package zelisline.ub.purchasing.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;

import zelisline.ub.purchasing.domain.RawPurchaseSession;

public interface RawPurchaseSessionRepository extends JpaRepository<RawPurchaseSession, String> {

    Optional<RawPurchaseSession> findByIdAndBusinessId(String id, String businessId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM RawPurchaseSession s WHERE s.id = :id AND s.businessId = :businessId")
    Optional<RawPurchaseSession> findByIdAndBusinessIdForUpdate(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    List<RawPurchaseSession> findByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status);

    List<RawPurchaseSession> findByBusinessIdAndSupplierIdAndStatusOrderByCreatedAtDesc(
            String businessId, String supplierId, String status);

    /**
     * Supplies the till must upload: posted Path B sessions never synced, or
     * edited locally since the last sync (mirror of the customer dirty query).
     */
    @Query("""
            select s from RawPurchaseSession s
             where s.businessId = :businessId
               and s.status = 'posted'
               and (s.cloudSyncedAt is null or s.updatedAt > s.cloudSyncedAt)
             order by s.receivedAt asc
            """)
    List<RawPurchaseSession> findDirtyForDesktopSync(@Param("businessId") String businessId);

    /** Cloud → till supplies pull: posted sessions touched at/after the cursor. */
    @Query("""
            select s from RawPurchaseSession s
             where s.businessId = :businessId
               and s.status = 'posted'
               and s.updatedAt >= :since
             order by s.updatedAt asc
            """)
    List<RawPurchaseSession> findForDesktopSyncPull(
            @Param("businessId") String businessId,
            @Param("since") java.time.Instant since,
            Pageable pageable);
}
