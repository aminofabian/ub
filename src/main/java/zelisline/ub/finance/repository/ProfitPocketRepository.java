package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.finance.domain.ProfitPocket;

public interface ProfitPocketRepository extends JpaRepository<ProfitPocket, String> {

    Optional<ProfitPocket> findByIdAndBusinessId(String id, String businessId);

    Optional<ProfitPocket> findByBusinessIdAndKopokopoSendMoneyId(String businessId, String kopokopoSendMoneyId);

    Optional<ProfitPocket> findFirstByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    List<ProfitPocket> findByBusinessIdOrderByCreatedAtDesc(String businessId, Pageable pageable);

    @Query("""
            select p from ProfitPocket p
             where p.businessId = :businessId
               and p.periodFrom <= :to
               and p.periodTo >= :from
             order by p.createdAt asc
            """)
    List<ProfitPocket> findOverlappingPeriod(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );
}
