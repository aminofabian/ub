package zelisline.ub.reporting.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import zelisline.ub.reporting.domain.MvSupplierMonthly;

public interface MvSupplierMonthlyRepository extends JpaRepository<MvSupplierMonthly, MvSupplierMonthly.Key> {

    /** Portable rollups (MySQL + H2): {@code YEAR}/{@code MONTH}, no {@code DATE_SUB}. */
    interface InvoiceMonthRollup {
        String getSupplierId();

        Integer getPeriodYear();

        Integer getPeriodMonth();

        BigDecimal getSpend();

        BigDecimal getQty();

        Number getInvoiceCount();
    }

    interface WastageMonthRollup {
        String getSupplierId();

        Integer getPeriodYear();

        Integer getPeriodMonth();

        BigDecimal getWastageQty();
    }

    interface MonthlySupplierAgg {
        String getSupplierId();

        LocalDate getCalendarMonth();

        BigDecimal getSpend();

        BigDecimal getQty();

        long getInvoiceCount();

        BigDecimal getWastageQty();
    }

    @Modifying
    @Query(value = "DELETE FROM mv_supplier_monthly WHERE business_id = :businessId", nativeQuery = true)
    int deleteForBusiness(@Param("businessId") String businessId);

    @Query(value = """
            SELECT si.supplier_id      AS supplierId,
                   YEAR(si.invoice_date) AS periodYear,
                   MONTH(si.invoice_date) AS periodMonth,
                   COALESCE(SUM(si.grand_total), 0) AS spend,
                   COALESCE(SUM(l.sum_qty), 0) AS qty,
                   COUNT(*) AS invoiceCount
              FROM supplier_invoices si
              LEFT JOIN (
                  SELECT invoice_id, SUM(qty) AS sum_qty
                    FROM supplier_invoice_lines
                   GROUP BY invoice_id
              ) l ON l.invoice_id = si.id
             WHERE si.business_id = :businessId
               AND si.status = 'posted'
             GROUP BY si.supplier_id, YEAR(si.invoice_date), MONTH(si.invoice_date)
            """, nativeQuery = true)
    List<InvoiceMonthRollup> rollupPostedInvoices(@Param("businessId") String businessId);

    @Query(value = """
            SELECT si.supplier_id      AS supplierId,
                   YEAR(si.invoice_date) AS periodYear,
                   MONTH(si.invoice_date) AS periodMonth,
                   COALESCE(SUM(si.grand_total), 0) AS spend,
                   COALESCE(SUM(l.sum_qty), 0) AS qty,
                   COUNT(*) AS invoiceCount
              FROM supplier_invoices si
              LEFT JOIN (
                  SELECT invoice_id, SUM(qty) AS sum_qty
                    FROM supplier_invoice_lines
                   GROUP BY invoice_id
              ) l ON l.invoice_id = si.id
             WHERE si.business_id = :businessId
               AND si.status = 'posted'
               AND YEAR(si.invoice_date) = :year
               AND MONTH(si.invoice_date) = :month
             GROUP BY si.supplier_id, YEAR(si.invoice_date), MONTH(si.invoice_date)
            """, nativeQuery = true)
    List<InvoiceMonthRollup> rollupPostedInvoicesForMonth(
            @Param("businessId") String businessId,
            @Param("year") int year,
            @Param("month") int month
    );

    @Query(value = """
            SELECT ib.supplier_id AS supplierId,
                   YEAR(sm.created_at) AS periodYear,
                   MONTH(sm.created_at) AS periodMonth,
                   COALESCE(SUM(ABS(sm.quantity_delta)), 0) AS wastageQty
              FROM stock_movements sm
              JOIN inventory_batches ib ON ib.id = sm.batch_id AND ib.business_id = sm.business_id
             WHERE sm.business_id = :businessId
               AND sm.movement_type = 'wastage'
             GROUP BY ib.supplier_id, YEAR(sm.created_at), MONTH(sm.created_at)
            """, nativeQuery = true)
    List<WastageMonthRollup> rollupWastage(@Param("businessId") String businessId);

    @Query(value = """
            SELECT ib.supplier_id AS supplierId,
                   YEAR(sm.created_at) AS periodYear,
                   MONTH(sm.created_at) AS periodMonth,
                   COALESCE(SUM(ABS(sm.quantity_delta)), 0) AS wastageQty
              FROM stock_movements sm
              JOIN inventory_batches ib ON ib.id = sm.batch_id AND ib.business_id = sm.business_id
             WHERE sm.business_id = :businessId
               AND sm.movement_type = 'wastage'
               AND YEAR(sm.created_at) = :year
               AND MONTH(sm.created_at) = :month
             GROUP BY ib.supplier_id, YEAR(sm.created_at), MONTH(sm.created_at)
            """, nativeQuery = true)
    List<WastageMonthRollup> rollupWastageForMonth(
            @Param("businessId") String businessId,
            @Param("year") int year,
            @Param("month") int month
    );

    @Query(value = """
            SELECT m.supplier_id       AS supplierId,
                   m.calendar_month    AS calendarMonth,
                   m.spend             AS spend,
                   m.qty               AS qty,
                   m.invoice_count     AS invoiceCount,
                   m.wastage_qty       AS wastageQty
              FROM mv_supplier_monthly m
             WHERE m.business_id = :businessId
               AND m.calendar_month >= :fromMonth
               AND m.calendar_month <= :toMonth
             ORDER BY m.calendar_month, m.supplier_id
            """, nativeQuery = true)
    List<MonthlySupplierAgg> listMvRange(
            @Param("businessId") String businessId,
            @Param("fromMonth") LocalDate fromMonth,
            @Param("toMonth") LocalDate toMonth
    );

    /**
     * OLTP rollups for a single calendar month (portable YEAR/MONTH), merged like {@code MvSupplierMonthlyRefresher}.
     */
    default List<MonthlySupplierAgg> listOltpForMonth(String businessId, LocalDate monthStart) {
        LocalDate cm = YearMonth.from(monthStart).atDay(1);
        List<InvoiceMonthRollup> inv = rollupPostedInvoicesForMonth(
                businessId, cm.getYear(), cm.getMonthValue());
        List<WastageMonthRollup> wastage = rollupWastageForMonth(
                businessId, cm.getYear(), cm.getMonthValue());
        return mergeOltpMonth(inv, wastage);
    }

    private static List<MonthlySupplierAgg> mergeOltpMonth(
            List<InvoiceMonthRollup> inv,
            List<WastageMonthRollup> wastage
    ) {
        Map<String, MvSupplierMonthlyMergedRow> merged = new LinkedHashMap<>();
        for (InvoiceMonthRollup row : inv) {
            LocalDate cm = LocalDate.of(row.getPeriodYear(), row.getPeriodMonth(), 1);
            long cnt = row.getInvoiceCount() == null ? 0L : row.getInvoiceCount().longValue();
            merged.put(key(row.getSupplierId(), cm), new MvSupplierMonthlyMergedRow(
                    row.getSupplierId(),
                    cm,
                    nz(row.getSpend()),
                    nzQty(row.getQty()),
                    cnt,
                    BigDecimal.ZERO
            ));
        }
        for (WastageMonthRollup w : wastage) {
            LocalDate cm = LocalDate.of(w.getPeriodYear(), w.getPeriodMonth(), 1);
            String k = key(w.getSupplierId(), cm);
            MvSupplierMonthlyMergedRow existing = merged.get(k);
            if (existing != null) {
                merged.put(k, existing.withWastage(nzQty(w.getWastageQty())));
            } else {
                merged.put(k, new MvSupplierMonthlyMergedRow(
                        w.getSupplierId(),
                        cm,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        0L,
                        nzQty(w.getWastageQty())
                ));
            }
        }
        return List.copyOf(merged.values());
    }

    private static String key(String supplierId, LocalDate cm) {
        return supplierId + "|" + cm;
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal nzQty(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}

final class MvSupplierMonthlyMergedRow implements MvSupplierMonthlyRepository.MonthlySupplierAgg {

    private final String supplierId;
    private final LocalDate calendarMonth;
    private final BigDecimal spend;
    private final BigDecimal qty;
    private final long invoiceCount;
    private final BigDecimal wastageQty;

    MvSupplierMonthlyMergedRow(
            String supplierId,
            LocalDate calendarMonth,
            BigDecimal spend,
            BigDecimal qty,
            long invoiceCount,
            BigDecimal wastageQty
    ) {
        this.supplierId = supplierId;
        this.calendarMonth = calendarMonth;
        this.spend = spend;
        this.qty = qty;
        this.invoiceCount = invoiceCount;
        this.wastageQty = wastageQty;
    }

    MvSupplierMonthlyMergedRow withWastage(BigDecimal wq) {
        return new MvSupplierMonthlyMergedRow(supplierId, calendarMonth, spend, qty, invoiceCount, wq);
    }

    @Override
    public String getSupplierId() {
        return supplierId;
    }

    @Override
    public LocalDate getCalendarMonth() {
        return calendarMonth;
    }

    @Override
    public BigDecimal getSpend() {
        return spend;
    }

    @Override
    public BigDecimal getQty() {
        return qty;
    }

    @Override
    public long getInvoiceCount() {
        return invoiceCount;
    }

    @Override
    public BigDecimal getWastageQty() {
        return wastageQty;
    }
}
