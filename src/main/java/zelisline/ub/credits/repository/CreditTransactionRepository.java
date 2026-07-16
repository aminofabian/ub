package zelisline.ub.credits.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.credits.domain.CreditTransaction;

public interface CreditTransactionRepository extends JpaRepository<CreditTransaction, String> {

    List<CreditTransaction> findByCreditAccountIdOrderByCreatedAtAsc(String creditAccountId);

    List<CreditTransaction> findByCreditAccountIdAndTxnTypeAndSaleIdIsNotNullOrderByCreatedAtDesc(
            String creditAccountId,
            String txnType,
            Pageable pageable
    );
}
