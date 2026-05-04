package zelisline.ub.reporting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Phase 7 Slice 3 — Report #5 supplier monthly spend (PHASE_7_PLAN.md). */
public record SupplierMonthlySpendResponse(
        LocalDate fromMonth,
        LocalDate toMonth,
        List<Row> rows,
        BigDecimal totalSpend,
        BigDecimal totalQty
) {

    public record Row(
            String supplierId,
            String supplierName,
            LocalDate calendarMonth,
            BigDecimal spend,
            BigDecimal qty,
            long invoiceCount,
            BigDecimal wastageQty
    ) {
    }
}
