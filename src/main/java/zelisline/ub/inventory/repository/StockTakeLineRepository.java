package zelisline.ub.inventory.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.inventory.domain.StockTakeLine;

public interface StockTakeLineRepository extends JpaRepository<StockTakeLine, String> {

    @Query("""
            select l from StockTakeLine l
             join fetch l.session s
             where s.businessId = :businessId
               and s.source = 'daily_audit'
               and l.reviewStatus = 'escalated'
               and (:branchId is null or s.branchId = :branchId)
               and (:from is null or s.sessionDate >= :from)
               and (:to is null or s.sessionDate <= :to)
             order by l.reviewedAt desc, s.sessionDate desc
            """)
    List<StockTakeLine> findEscalatedDailyAuditLines(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
