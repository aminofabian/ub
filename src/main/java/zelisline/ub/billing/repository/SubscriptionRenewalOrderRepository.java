package zelisline.ub.billing.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.billing.domain.SubscriptionRenewalOrder;
import zelisline.ub.billing.domain.SubscriptionRenewalOrderStatus;

public interface SubscriptionRenewalOrderRepository extends JpaRepository<SubscriptionRenewalOrder, String> {

    Optional<SubscriptionRenewalOrder> findByIdAndBusinessId(String id, String businessId);

    Optional<SubscriptionRenewalOrder> findByMpesaReceipt(String mpesaReceipt);

    long countByStatusAndPaidAtAfter(SubscriptionRenewalOrderStatus status, Instant paidAt);

    @Query("""
        select coalesce(sum(o.amountKes), 0) from SubscriptionRenewalOrder o
         where o.status = :status and o.paidAt >= :paidAt
        """)
    BigDecimal sumAmountByStatusAndPaidAtAfter(
            @Param("status") SubscriptionRenewalOrderStatus status,
            @Param("paidAt") Instant paidAt);
}
