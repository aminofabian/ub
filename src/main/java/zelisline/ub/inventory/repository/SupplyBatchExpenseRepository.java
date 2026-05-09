package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.SupplyBatchExpense;

public interface SupplyBatchExpenseRepository extends JpaRepository<SupplyBatchExpense, String> {

    List<SupplyBatchExpense> findBySupplyBatchIdOrderByCreatedAtAsc(String supplyBatchId);

    @Query("""
            select coalesce(sum(e.amount), 0)
            from SupplyBatchExpense e
            where e.supplyBatchId = :supplyBatchId
              and e.businessId = :businessId
            """)
    BigDecimal sumBySupplyBatchId(
            @Param("supplyBatchId") String supplyBatchId,
            @Param("businessId") String businessId
    );
}
