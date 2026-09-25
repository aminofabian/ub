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
     * {@code from} and {@code to} inclusive. Pass {@code null} {@code branchId} for
     * all branches (includes unallocated journals); a non-null branch returns only
     * entries tagged with that branch.
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
               and (:branchId is null or je.branch_id = :branchId)
             group by la.id, la.code, la.name, la.account_type
            """, nativeQuery = true)
    List<AccountBalance> sumByAccountForPeriod(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId
    );

    /**
     * Per-account totals for every entry on or before {@code asOf} — drives the
     * balance sheet (`assets = liabilities + equity` identity). Same branch rule as
     * {@link #sumByAccountForPeriod}.
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
               and (:branchId is null or je.branch_id = :branchId)
             group by la.id, la.code, la.name, la.account_type
            """, nativeQuery = true)
    List<AccountBalance> sumByAccountAsOf(
            @Param("businessId") String businessId,
            @Param("asOf") LocalDate asOf,
            @Param("branchId") String branchId
    );

    /**
     * Revenue, COGS, profit, and sale count for completed sales whose {@code sold_at} falls
     * within the supplied Instant window. Pulse passes business-TZ midnight bounds
     * ({@code BusinessTimeZones} / ENP-3), not UTC midnight of the calendar date.
     * Pass {@code null} for {@code branchId} / {@code itemTypeId} to aggregate across the
     * tenant (or all departments).
     */
    @Query(value = """
            select coalesce(sum(si.line_total), 0) as revenue,
                   coalesce(sum(si.cost_total), 0) as cogs,
                   coalesce(sum(si.profit), 0)     as profit,
                   count(distinct s.id)             as saleCount
              from sales s
              join sale_items si on si.sale_id = s.id
              left join items i on i.id = si.item_id and i.business_id = s.business_id and i.deleted_at is null
             where s.business_id = :businessId
               and s.status = 'completed'
               and s.sold_at >= :startInclusive
               and s.sold_at <  :endExclusive
               and (:branchId is null or s.branch_id = :branchId)
               and (:itemTypeId is null or i.item_type_id = :itemTypeId)
            """, nativeQuery = true)
    SaleAggregate sumSalesForWindow(
            @Param("businessId") String businessId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    /**
     * Period rollup used when P&amp;L is scoped to a department (journal lines have no
     * item-type dimension). Same status/date rules as the sales register OLTP path.
     */
    @Query(value = """
            select coalesce(sum(si.line_total), 0) as revenue,
                   coalesce(sum(si.cost_total), 0) as cogs,
                   coalesce(sum(si.profit), 0)     as profit,
                   count(distinct s.id)             as saleCount
              from sales s
              join sale_items si on si.sale_id = s.id
              join items i on i.id = si.item_id and i.business_id = s.business_id and i.deleted_at is null
             where s.business_id = :businessId
               and s.status = 'completed'
               and cast(s.sold_at as date) >= :from
               and cast(s.sold_at as date) <= :to
               and (:branchId is null or s.branch_id = :branchId)
               and (:itemTypeId is null or i.item_type_id = :itemTypeId)
            """, nativeQuery = true)
    SaleAggregate sumSalesForPeriod(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId,
            @Param("itemTypeId") String itemTypeId
    );

    interface SaleAggregate {
        BigDecimal getRevenue();
        BigDecimal getCogs();
        BigDecimal getProfit();
        long getSaleCount();
    }

    /**
     * Refund revenue / COGS / profit reversed within an {@code Instant} window. Used to net
     * refunds into the pulse so the "today" card treats refunds the way the ledger P&amp;L does.
     * Refunds whose sale is already fully refunded ({@code s.status = 'refunded'}) are excluded —
     * that sale is outside the gross aggregate too, so netting it again would double-count.
     */
    @Query(value = """
            select coalesce(sum(rl.amount), 0) as refundRevenue,
                   coalesce(sum(si.cost_total * (rl.quantity / nullif(si.quantity, 0))), 0) as refundCogs,
                   coalesce(sum(si.profit * (rl.quantity / nullif(si.quantity, 0))), 0) as refundProfit,
                   count(distinct r.id) as refundCount
              from refund_lines rl
              join refunds r on r.id = rl.refund_id
              join sale_items si on si.id = rl.sale_item_id
              join sales s on s.id = si.sale_id
             where r.business_id = :businessId
               and r.status = 'completed'
               and s.status = 'completed'
               and r.refunded_at >= :startInclusive
               and r.refunded_at <  :endExclusive
               and (:branchId is null or s.branch_id = :branchId)
            """, nativeQuery = true)
    RefundAggregate sumRefundsForWindow(
            @Param("businessId") String businessId,
            @Param("startInclusive") Instant startInclusive,
            @Param("endExclusive") Instant endExclusive,
            @Param("branchId") String branchId
    );

    interface RefundAggregate {
        BigDecimal getRefundRevenue();
        BigDecimal getRefundCogs();
        BigDecimal getRefundProfit();
        long getRefundCount();
    }

    /**
     * Gross profit by sale date for the pocketing calendar. Uses the same
     * {@code cast(sold_at as date)} window as {@link #sumSalesForPeriod}.
     */
    @Query(value = """
            select cast(s.sold_at as date)          as day,
                   coalesce(sum(si.profit), 0)       as profit,
                   count(distinct s.id)              as saleCount
              from sales s
              join sale_items si on si.sale_id = s.id
             where s.business_id = :businessId
               and s.status = 'completed'
               and cast(s.sold_at as date) >= :from
               and cast(s.sold_at as date) <= :to
               and (:branchId is null or s.branch_id = :branchId)
             group by cast(s.sold_at as date)
            """, nativeQuery = true)
    List<DailyProfitRow> sumProfitByDay(
            @Param("businessId") String businessId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to,
            @Param("branchId") String branchId
    );

    interface DailyProfitRow {
        Object getDay();
        BigDecimal getProfit();
        Number getSaleCount();
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
