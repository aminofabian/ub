package zelisline.ub.inventory.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockTakeSession;

public interface StockTakeSessionRepository extends JpaRepository<StockTakeSession, String> {

    @Query("""
            select distinct s from StockTakeSession s
             left join fetch s.lines
             where s.id = :id and s.businessId = :businessId
            """)
    Optional<StockTakeSession> findByIdAndBusinessIdFetchLines(
            @Param("id") String id,
            @Param("businessId") String businessId
    );
}
