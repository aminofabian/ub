package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PathBSessionListRow(
        String id,
        String supplierId,
        String branchId,
        Instant receivedAt,
        String status,
        int lineCount,
        BigDecimal totalAmount
) {
}
