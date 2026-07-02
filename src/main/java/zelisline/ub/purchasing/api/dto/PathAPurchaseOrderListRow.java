package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PathAPurchaseOrderListRow(
        String id,
        String supplierId,
        String branchId,
        String poNumber,
        LocalDate expectedDate,
        String status,
        int lineCount,
        BigDecimal totalOrdered,
        BigDecimal totalReceived
) {
}
