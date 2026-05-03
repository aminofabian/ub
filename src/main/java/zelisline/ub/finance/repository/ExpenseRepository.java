package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.finance.domain.Expense;

public interface ExpenseRepository extends JpaRepository<Expense, String> {

    Optional<Expense> findByIdAndBusinessId(String id, String businessId);

    List<Expense> findByBusinessIdAndExpenseDateOrderByCreatedAtDesc(String businessId, LocalDate expenseDate);

    @Query("""
            select coalesce(sum(e.amount), 0)
              from Expense e
             where e.businessId = :businessId
               and e.branchId = :branchId
               and e.includeInCashDrawer = true
               and e.paymentMethod = 'cash'
               and e.createdAt >= :openedAt
               and e.createdAt <= :closedAt
            """)
    java.math.BigDecimal sumDrawerCashExpensesForShiftWindow(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId,
            @Param("openedAt") Instant openedAt,
            @Param("closedAt") Instant closedAt
    );
}

