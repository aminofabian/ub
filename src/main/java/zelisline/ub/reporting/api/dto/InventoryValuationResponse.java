package zelisline.ub.reporting.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Report #6 — inventory valuation by branch (Phase 7 Slice 4). */
public record InventoryValuationResponse(
        String branchId,
        List<Row> rows,
        BigDecimal totalQty,
        BigDecimal totalFifoValue
) {

    public record Row(
            String branchId,
            String itemId,
            String itemName,
            BigDecimal qtyOnHand,
            BigDecimal fifoValue,
            LocalDate earliestExpiry
    ) {
    }
}
