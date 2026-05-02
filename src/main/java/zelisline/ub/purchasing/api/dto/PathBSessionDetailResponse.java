package zelisline.ub.purchasing.api.dto;

import java.time.Instant;
import java.util.List;

public record PathBSessionDetailResponse(
        String id,
        String supplierId,
        String branchId,
        Instant receivedAt,
        String notes,
        String status,
        List<PathBLineResponse> lines
) {
}
