package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PathAPurchaseOrderLineResponse(
        String id,
        int sortOrder,
        String itemId,
        BigDecimal qtyOrdered,
        BigDecimal qtyReceived,
        BigDecimal unitEstimatedCost
) {
}
