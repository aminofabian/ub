package zelisline.ub.restaurant.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.restaurant.domain.KitchenPrintJob;

public interface KitchenPrintJobRepository extends JpaRepository<KitchenPrintJob, String> {

    /**
     * Jobs a branch still has to print, oldest first. A station screen asks for its own station (and
     * the implicit kitchen, {@code station_id is null}); the counter asks for the whole branch with
     * {@code stationId} null. Only jobs young enough to still matter are returned.
     */
    @Query("""
            select j from KitchenPrintJob j
             where j.businessId = :businessId
               and j.branchId = :branchId
               and j.claimedAt is null
               and j.createdAt >= :cutoff
               and (:stationId is null or j.stationId = :stationId or j.stationId is null)
             order by j.createdAt asc
            """)
    List<KitchenPrintJob> findPending(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("stationId") String stationId,
            @Param("cutoff") Instant cutoff);

    /**
     * Claim a job for one device. The {@code claimedAt is null} guard makes this exclusive: exactly
     * one caller gets {@code 1} back, the rest get {@code 0} and must leave the job to whoever won.
     * Scoped by id and business; the job's branch is its own and does not need naming here.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update KitchenPrintJob j
               set j.claimedAt = :now, j.claimedBy = :userId
             where j.id = :id
               and j.businessId = :businessId
               and j.claimedAt is null
               and j.createdAt >= :cutoff
            """)
    int claimIfOpen(
            @Param("id") String id,
            @Param("businessId") String businessId,
            @Param("userId") String userId,
            @Param("now") Instant now,
            @Param("cutoff") Instant cutoff);

    /**
     * Jobs the counter should warn about: old enough that they should have printed, but neither
     * printed nor recently claimed (a claim that never confirmed is counted again after a grace
     * period, so a crashed device does not hide an unprinted ticket for ever). A reprint is left
     * out: it is a deliberate re-queue, and the original already warned if it never printed.
     */
    @Query("""
            select j from KitchenPrintJob j
             where j.businessId = :businessId
               and j.branchId = :branchId
               and j.printedAt is null
               and j.reprintOf is null
               and j.createdAt >= :floor
               and j.createdAt <= :warnBefore
               and (j.claimedAt is null or j.claimedAt <= :staleClaim)
             order by j.createdAt asc
            """)
    List<KitchenPrintJob> findUnprinted(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("floor") Instant floor,
            @Param("warnBefore") Instant warnBefore,
            @Param("staleClaim") Instant staleClaim);

    java.util.Optional<KitchenPrintJob> findByIdAndBusinessId(String id, String businessId);

    boolean existsByBusinessIdAndTicketId(String businessId, String ticketId);
}
