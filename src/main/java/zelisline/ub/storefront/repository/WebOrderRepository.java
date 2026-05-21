package zelisline.ub.storefront.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
