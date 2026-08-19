package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record DiscountPreviewResponse(
        long affectedCount,
        List<DiscountPreviewLine> sample,
        BigDecimal minSavedPerItem,
        BigDecimal maxSavedPerItem,
        List<String> warnings,
        List<String> errors
) {
}
