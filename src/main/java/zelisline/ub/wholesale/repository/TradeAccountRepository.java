package zelisline.ub.wholesale.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.wholesale.domain.TradeAccount;

public interface TradeAccountRepository extends JpaRepository<TradeAccount, String> {

    Optional<TradeAccount> findByIdAndBusinessId(String id, String businessId);

    @org.springframework.data.jpa.repository.Query("""
            select a from TradeAccount a
             where a.businessId = :businessId and a.updatedAt >= :since
             order by a.updatedAt asc
            """)
    java.util.List<TradeAccount> findForDesktopSyncPull(
            @org.springframework.data.repository.query.Param("businessId") String businessId,
            @org.springframework.data.repository.query.Param("since") java.time.Instant since,
            org.springframework.data.domain.Pageable pageable);

    Optional<TradeAccount> findByBusinessIdAndPhoneKey(String businessId, String phoneKey);

    Optional<TradeAccount> findByBusinessIdAndBuyerBusinessId(String businessId, String buyerBusinessId);

    List<TradeAccount> findTop200ByBusinessIdOrderByCreatedAtDesc(String businessId);

    List<TradeAccount> findTop200ByBusinessIdAndStatusOrderByCreatedAtDesc(String businessId, String status);
}
