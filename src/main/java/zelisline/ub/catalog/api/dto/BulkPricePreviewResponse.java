package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record BulkPricePreviewResponse(
        int matched,
        int affected,
        int skippedExisting,
        int unchanged,
        int losses,
        int lowMargin,
        BigDecimal lowMarginPct,
        boolean truncated,
        boolean requiresLossAcknowledgement,
        List<BulkPricePreviewRow> rows
) {
}
