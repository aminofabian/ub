package zelisline.ub.sales.api.dto;

import java.time.Instant;

import zelisline.ub.sales.domain.CashDrawerOpenReason;
import zelisline.ub.sales.domain.CashDrawerOpenSource;

public record CashDrawerOpenResponse(
        String id,
        String clientEventId,
        String branchId,
        String shiftId,
        String saleId,
        Instant openedAt,
        Instant closedAt,
        CashDrawerOpenSource source,
        CashDrawerOpenReason reason,
        boolean sensorConfirmed,
        String performedBy,
        String tillDeviceKey
) {
}
