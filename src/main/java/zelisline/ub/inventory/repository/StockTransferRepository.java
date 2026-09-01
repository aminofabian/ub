package zelisline.ub.inventory.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockTransfer;

public interface StockTransferRepository extends JpaRepository<StockTransfer, String> {

    @Query("""
            select distinct t from StockTransfer t
             left join fetch t.lines
             where t.id = :id and t.businessId = :businessId
            """)
    Optional<StockTransfer> findByIdAndBusinessIdFetchLines(
            @Param("id") String id,
            @Param("businessId") String businessId
    );

    /** Newest first; optional status / branch filter (branch matches from OR to). */
    @Query("""
            select distinct t from StockTransfer t
             left join fetch t.lines
             where t.businessId = :businessId
               and (:status is null or t.status = :status)
               and (:branchId is null or t.fromBranchId = :branchId or t.toBranchId = :branchId)
             order by t.createdAt desc
            """)
    List<StockTransfer> findByBusinessIdFiltered(
            @Param("businessId") String businessId,
            @Param("status") String status,
            @Param("branchId") String branchId
    );
}
