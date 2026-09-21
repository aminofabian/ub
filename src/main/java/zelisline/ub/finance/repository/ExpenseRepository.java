package zelisline.ub.finance.repository;

import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.finance.domain.Expense;

public interface ExpenseRepository extends JpaRepository<Expense, String> {

    Optional<Expense> findByIdAndBusinessId(String id, String businessId);

    List<Expense> findByBusinessIdAndExpenseDateOrderByCreatedAtDesc(String businessId, LocalDate expenseDate);

    @Query("""
            select e from Expense e
             where e.businessId = :businessId
               and e.expenseDate >= :from
               and e.expenseDate <= :to
               and (:branchId is null or e.branchId = :branchId)
               and (:categoryType is null or e.categoryType = :categoryType)
               and (:categoryCode is null or e.categoryCode = :categoryCode)
               and (:source is null or e.source = :source)
               and (:approvalStatus is null or e.approvalStatus = :approvalStatus)
               and (:q is null or lower(e.name) like lower(concat('%', :q, '%')))
            """)
    Page<Expense> findForPeriod(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId,
            @Param("categoryType") String categoryType,
            @Param("categoryCode") String categoryCode,
            @Param("source") String source,
            @Param("approvalStatus") String approvalStatus,
            @Param("q") String q,
            Pageable pageable
    );

    @Query("""
            select coalesce(sum(e.amount), 0)
              from Expense e
             where e.businessId = :businessId
               and e.branchId = :branchId
               and e.includeInCashDrawer = true
               and e.paymentMethod = 'cash'
               and e.approvalStatus = 'posted'
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
