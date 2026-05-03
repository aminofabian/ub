package zelisline.ub.credits.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.credits.domain.LoyaltyTransaction;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, String> {

    List<LoyaltyTransaction> findBySaleIdOrderByCreatedAtAsc(String saleId);

    List<LoyaltyTransaction> findByCreditAccountIdOrderByCreatedAtDesc(String creditAccountId);

    List<LoyaltyTransaction> findByCreditAccountIdOrderByCreatedAtAsc(String creditAccountId);

    @Query("select coalesce(sum(t.points), 0) from LoyaltyTransaction t "
            + "where t.saleId = :saleId and t.txnType = :type")
    long sumPointsBySaleAndType(@Param("saleId") String saleId, @Param("type") String txnType);
}
