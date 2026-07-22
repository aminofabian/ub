package zelisline.ub.globalcatalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record RefreshCatalogResponse(
        int updatedCount,
        int skippedCount,
        List<RefreshCatalogLineResponse> lines
) {
}
