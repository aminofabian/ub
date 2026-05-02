package zelisline.ub.purchasing.api.dto;

import java.time.LocalDate;
import java.util.List;

public record PathAPurchaseOrderDetailResponse(
        String id,
        String supplierId,
        String branchId,
        String poNumber,
        LocalDate expectedDate,
        String status,
        String notes,
        List<PathAPurchaseOrderLineResponse> lines
) {
}
