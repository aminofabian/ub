package zelisline.ub.inventory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockCountCycle;

public interface StockCountCycleRepository extends JpaRepository<StockCountCycle, String> {

    @Query("""
            select distinct c from StockCountCycle c
             left join fetch c.items
             where c.id = :id and c.businessId = :businessId
            """)
    Optional<StockCountCycle> findByIdAndBusinessIdFetchItems(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    @Query("""
            select c from StockCountCycle c
             where c.businessId = :businessId
               and c.branchId = :branchId
               and c.status <> 'cancelled'
             order by c.startedAt desc
            """)
    List<StockCountCycle> findLatestForBranch(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );
}
