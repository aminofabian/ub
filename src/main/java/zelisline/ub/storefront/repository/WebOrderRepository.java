package zelisline.ub.storefront.repository;

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
}
