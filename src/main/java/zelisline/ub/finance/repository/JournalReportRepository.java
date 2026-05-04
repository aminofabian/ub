package zelisline.ub.finance.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.finance.domain.JournalLine;

/**
 * Read-only aggregations for the finance reports surface (Phase 7 Slice 0 onwards).
 * Keeps the write-side {@link JournalLineRepository} small and focused.
 *
 * <p>All queries scope by {@code business_id} via the join on {@code journal_entries};
 * RLS is enforced at the application layer (every controller resolves the tenant
 * before invoking these methods).</p>
 */
public interface JournalReportRepository extends JpaRepository<JournalLine, String> {

    interface AccountBalance {
        String getLedgerAccountId();
        String getCode();
        String getName();
        String getAccountType();
        BigDecimal getDebitTotal();
        BigDecimal getCreditTotal();
    }

    /**
     * Per-account debit/credit totals for entries with {@code entry_date} between
     * {@code from} and {@code to} inclusive. Single round-trip; the service applies
     * the sign convention per account type.
     */
    @Query(value = """
            select la.id              as ledgerAccountId,
                   la.code             as code,
                   la.name             as name,
                   la.account_type     as accountType,
                   coalesce(sum(jl.debit), 0)  as debitTotal,
                   coalesce(sum(jl.credit), 0) as creditTotal
              from journal_lines jl
              join journal_entries je on je.id = jl.journal_entry_id
              join ledger_accounts la on la.id = jl.ledger_account_id
             where je.business_id = :businessId
               and je.entry_date >= :from
               and je.entry_date <= :to
             group by la.id, la.code, la.name, la.account_type
            """, nativeQuery = true)
    List<AccountBalance> sumByAccountForPeriod(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to
    );

    /**
     * Per-account totals for every entry on or before {@code asOf} — drives the
     * balance sheet (`assets = liabilities + equity` identity).
     */
    @Query(value = """
            select la.id              as ledgerAccountId,
                   la.code             as code,
                   la.name             as name,
                   la.account_type     as accountType,
                   coalesce(sum(jl.debit), 0)  as debitTotal,
                   coalesce(sum(jl.credit), 0) as creditTotal
              from journal_lines jl
              join journal_entries je on je.id = jl.journal_entry_id
              join ledger_accounts la on la.id = jl.ledger_account_id
             where je.business_id = :businessId
               and je.entry_date <= :asOf
             group by la.id, la.code, la.name, la.account_type
            """, nativeQuery = true)
    List<AccountBalance> sumByAccountAsOf(
            @Param("businessId") String businessId,
            @Param("asOf") LocalDate asOf
    );

    /**
     * Revenue, COGS, profit, and sale count for completed sales whose {@code sold_at} falls
     * within the supplied UTC window — pulse uses the OLTP side (PHASE_7_PLAN.md In Scope:
     * "today" hybrid). Pass {@code null} for {@code branchId} to aggregate across the tenant.
     */
    @Query(value = """
            select coalesce(sum(si.line_total), 0) as revenue,
                   coalesce(sum(si.cost_total), 0) as cogs,
                   coalesce(sum(si.profit), 0)     as profit,
                   count(distinct s.id)             as saleCount
              from sales s
              join sale_items si on si.sale_id = s.id
             where s.business_id = :businessId
               and s.status = 'completed'
               and s.sold_at >= :startInclusive
               and s.sold_at <  :endExclusive
               and (:branchId is null or s.branch_id = :branchId)
            """, nativeQuery = true)
    SaleAggregate sumSalesForWindow(
            @Param("businessId") String businessId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("branchId") String branchId
    );

    interface SaleAggregate {
        BigDecimal getRevenue();
        BigDecimal getCogs();
        BigDecimal getProfit();
        long getSaleCount();
    }

    @Query(value = """
            select count(*)
              from shifts s
             where s.business_id = :businessId
               and s.status = 'open'
               and (:branchId is null or s.branch_id = :branchId)
            """, nativeQuery = true)
    long countOpenShifts(
            @Param("businessId") String businessId,
            @Param("branchId") String branchId
    );

    @Query(value = """
            select coalesce(sum(e.amount), 0)
              from expenses e
             where e.business_id = :businessId
               and e.expense_date = :date
               and (:branchId is null or e.branch_id = :branchId)
            """, nativeQuery = true)
    BigDecimal sumExpensesForDate(
            @Param("businessId") String businessId,
            @Param("date") LocalDate date,
            @Param("branchId") String branchId
    );
}
