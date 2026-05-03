package zelisline.ub.inventory.repository;

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
}
