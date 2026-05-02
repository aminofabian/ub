package zelisline.ub.purchasing.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.purchasing.domain.RawPurchaseLine;

public interface RawPurchaseLineRepository extends JpaRepository<RawPurchaseLine, String> {

    List<RawPurchaseLine> findBySessionIdOrderBySortOrderAscIdAsc(String sessionId);

    int countBySessionIdAndLineStatus(String sessionId, String lineStatus);

    @Query("select coalesce(max(l.sortOrder), -1) from RawPurchaseLine l where l.sessionId = :sid")
    int maxSortOrder(@Param("sid") String sessionId);
}
