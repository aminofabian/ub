package zelisline.ub.sales.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.sales.domain.RefundLine;

public interface RefundLineRepository extends JpaRepository<RefundLine, String> {

    @Query("""
            select coalesce(sum(rl.quantity), 0)
            from RefundLine rl
            join Refund r on r.id = rl.refundId
            where rl.saleItemId = :saleItemId and r.saleId = :saleId
            """)
    BigDecimal sumRefundedQuantityForSaleItem(
            @Param("saleItemId") String saleItemId,
            @Param("saleId") String saleId
    );

    List<RefundLine> findByRefundIdOrderByIdAsc(String refundId);
}
