package zelisline.ub.sales.repository;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.RefundPayment;

public interface RefundPaymentRepository extends JpaRepository<RefundPayment, String> {

    List<RefundPayment> findByRefundIdOrderBySortOrderAsc(String refundId);

    @Query("""
            select coalesce(sum(rp.amount), 0)
              from RefundPayment rp
              join Refund r on r.id = rp.refundId
              join Sale s on s.id = r.saleId
             where r.businessId = :businessId
               and s.branchId = :branchId
               and s.shiftId = :shiftId
               and r.refundedAt >= :openedAt
               and r.refundedAt <= :closedAt
               and r.status = 'completed'
               and rp.method = 'cash'
            """)
    BigDecimal sumCashRefundForShiftWindow(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("shiftId") String shiftId,
            @Param("openedAt") Instant openedAt,
            @Param("closedAt") Instant closedAt
    );
}
