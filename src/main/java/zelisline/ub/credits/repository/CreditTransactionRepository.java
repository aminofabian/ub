package zelisline.ub.credits.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.credits.domain.CreditTransaction;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, String> {

    List<CreditTransaction> findByCreditAccountIdOrderByCreatedAtAsc(String creditAccountId);

    List<CreditTransaction> findByCreditAccountIdAndTxnTypeAndSaleIdIsNotNullOrderByCreatedAtDesc(
            String creditAccountId,
            String txnType,
            Pageable pageable
    );

    @Query("""
            select coalesce(sum(t.amount), 0)
            from CreditTransaction t
            where t.businessId = :businessId
              and t.txnType = :txnType
              and t.createdAt >= :fromInclusive
              and t.createdAt < :toExclusive
            """)
    BigDecimal sumAmountByBusinessIdAndTxnTypeAndCreatedAtRange(
            @Param("businessId") String businessId,
            @Param("txnType") String txnType,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive
    );

    @Query("""
            select count(t)
            from CreditTransaction t
            where t.businessId = :businessId
              and t.txnType = :txnType
              and t.createdAt >= :fromInclusive
              and t.createdAt < :toExclusive
            """)
    long countByBusinessIdAndTxnTypeAndCreatedAtRange(
            @Param("businessId") String businessId,
            @Param("txnType") String txnType,
            @Param("fromInclusive") Instant fromInclusive,
            @Param("toExclusive") Instant toExclusive
    );
}
