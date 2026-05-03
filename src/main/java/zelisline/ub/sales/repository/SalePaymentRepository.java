package zelisline.ub.sales.repository;

import java.util.List;
import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.SalePayment;

public interface SalePaymentRepository extends JpaRepository<SalePayment, String> {

    List<SalePayment> findBySaleIdOrderBySortOrderAsc(String saleId);

    @Query("""
            select coalesce(sum(sp.amount), 0)
              from SalePayment sp
              join Sale s on s.id = sp.saleId
             where s.businessId = :businessId
               and s.branchId = :branchId
               and s.shiftId = :shiftId
               and s.soldAt >= :openedAt
               and s.soldAt <= :closedAt
               and s.status = 'completed'
               and sp.method = 'cash'
            """)
    BigDecimal sumCashTenderForShiftWindow(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("shiftId") String shiftId,
            @Param("openedAt") Instant openedAt,
            @Param("closedAt") Instant closedAt
    );
}
