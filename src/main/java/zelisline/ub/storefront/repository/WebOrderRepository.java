package zelisline.ub.storefront.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.storefront.domain.WebOrder;

public interface WebOrderRepository extends JpaRepository<WebOrder, String> {

    Page<WebOrder> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    @Query(
            """
                    select w from WebOrder w
                     where w.businessId = :businessId
                       and lower(trim(w.customerEmail)) = lower(trim(:customerEmailNorm))
                     order by w.createdAt desc
                    """)
    Page<WebOrder> findShopperOrdersByBusinessIdAndNormalizedEmail(
            @Param("businessId") String businessId,
            @Param("customerEmailNorm") String customerEmailNormalized,
            Pageable pageable
    );

    Optional<WebOrder> findByIdAndBusinessId(String id, String businessId);

    /** WhatsApp orders still awaiting confirmation whose stock window has lapsed (scope §11). */
    @Query("""
            select w from WebOrder w
             where w.channel = 'WHATSAPP'
               and w.status = 'pending_payment'
               and (w.fulfillmentStatus is null or w.fulfillmentStatus = 'awaiting_confirmation')
               and w.expiresAt is not null
               and w.expiresAt < :now
               and (w.handoffState is null or w.handoffState <> 'expired')
             order by w.expiresAt asc
            """)
    List<WebOrder> findExpiredUnconfirmedWhatsAppOrders(@Param("now") Instant now);

    /**
     * Atomically claim a one-time pickup-ticket auto-print.
     * Succeeds only when never printed and the order is newer than {@code minCreatedAt}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update WebOrder w
               set w.pickupTicketPrintedAt = :now,
                   w.updatedAt = :now
             where w.id = :id
               and w.businessId = :businessId
               and w.pickupTicketPrintedAt is null
               and w.createdAt >= :minCreatedAt
            """)
    int claimPickupTicketPrint(
            @Param("id") String id,
            @Param("businessId") String businessId,
            @Param("now") Instant now,
            @Param("minCreatedAt") Instant minCreatedAt);

    interface InactiveShopperEmail {
        String getEmail();
    }

    @Query("""
            select lower(trim(w.customerEmail)) as email
              from WebOrder w
             where w.businessId = :businessId
               and w.customerEmail is not null
               and trim(w.customerEmail) <> ''
             group by lower(trim(w.customerEmail))
            having max(w.createdAt) < :cutoff
            """)
    List<InactiveShopperEmail> findInactiveShopperEmails(
            @Param("businessId") String businessId,
            @Param("cutoff") Instant cutoff);

    interface RecentShopperEmail {
        String getEmail();
    }

    @Query("""
            select lower(trim(w.customerEmail)) as email
              from WebOrder w
             where w.businessId = :businessId
               and w.customerEmail is not null
               and trim(w.customerEmail) <> ''
               and w.createdAt >= :since
             group by lower(trim(w.customerEmail))
            """)
    List<RecentShopperEmail> findRecentShopperEmails(
            @Param("businessId") String businessId,
            @Param("since") Instant since);

    @Query("""
            select lower(trim(w.customerEmail)) as email
              from WebOrder w
             where w.businessId = :businessId
               and w.catalogBranchId = :branchId
               and w.customerEmail is not null
               and trim(w.customerEmail) <> ''
               and w.createdAt >= :since
             group by lower(trim(w.customerEmail))
            """)
    List<RecentShopperEmail> findRecentShopperEmailsByBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("since") Instant since);

    @Query("""
            select COUNT(*),
                   COALESCE(SUM(w.grandTotal), 0)
              from WebOrder w
             where w.businessId = :businessId
               and w.paidAt is not null
               and w.paidAt >= :since
               and w.paidAt < :until
            """)
    List<Object[]> aggregatePaidBetween(
            @Param("businessId") String businessId,
            @Param("since") Instant since,
            @Param("until") Instant until);

    @Query("""
            select COUNT(*),
                   COALESCE(SUM(w.grandTotal), 0)
              from WebOrder w
             where w.businessId = :businessId
               and w.paidAt is not null
            """)
    List<Object[]> aggregatePaidAllTime(@Param("businessId") String businessId);
}
