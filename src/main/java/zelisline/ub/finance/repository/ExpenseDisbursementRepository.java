package zelisline.ub.finance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import zelisline.ub.finance.domain.ExpenseDisbursement;

public interface ExpenseDisbursementRepository extends JpaRepository<ExpenseDisbursement, String> {

    Optional<ExpenseDisbursement> findFirstByBusinessIdAndExpenseIdAndStatusOrderByCreatedAtDesc(
            String businessId,
            String expenseId,
            String status
    );

    Optional<ExpenseDisbursement> findByKopokopoSendMoneyId(String kopokopoSendMoneyId);

    List<ExpenseDisbursement> findByBusinessIdAndExpenseIdOrderByCreatedAtDesc(
            String businessId,
            String expenseId
    );

    List<ExpenseDisbursement> findByStatusInAndCreatedAtAfterOrderByCreatedAtAsc(
            Collection<String> statuses,
            Instant createdAfter
    );
}
