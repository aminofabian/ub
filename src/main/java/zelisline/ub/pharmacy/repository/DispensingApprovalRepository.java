package zelisline.ub.pharmacy.repository;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import zelisline.ub.pharmacy.domain.DispensingApproval;

public interface DispensingApprovalRepository extends JpaRepository<DispensingApproval, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from DispensingApproval a where a.id = :id and a.businessId = :businessId")
    Optional<DispensingApproval> findByIdAndBusinessIdForUpdate(
            @Param("id") String id, @Param("businessId") String businessId);

    /**
     * Ends every approval a cashier still holds unused, so only the latest one can be spent.
     * An approval left over from an abandoned sale would otherwise stay usable until it expires.
     */
    @Transactional
    @Modifying
    @Query("""
            update DispensingApproval a
               set a.expiresAt = :now
             where a.businessId = :businessId
               and a.requestedBy = :requestedBy
               and a.usedAt is null
               and a.expiresAt > :now
            """)
    int expireOpenForRequester(
            @Param("businessId") String businessId,
            @Param("requestedBy") String requestedBy,
            @Param("now") Instant now);
}
