package zelisline.ub.inventory.repository;

import java.math.BigDecimal;
import java.util.Collection;
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

    /**
     * Sums extra costs on Path B supply batches keyed by session ({@code sourceId}).
     * Returns rows of [sourceId, amountSum].
     */
    @Query("""
            select sb.sourceId, coalesce(sum(e.amount), 0)
            from SupplyBatchExpense e
            join SupplyBatch sb on sb.id = e.supplyBatchId
            where e.businessId = :businessId
              and sb.businessId = :businessId
              and sb.sourceType = :sourceType
              and sb.sourceId in :sourceIds
            group by sb.sourceId
            """)
    List<Object[]> sumAmountGroupedBySourceId(
            @Param("businessId") String businessId,
            @Param("sourceType") String sourceType,
            @Param("sourceIds") Collection<String> sourceIds
    );
}
