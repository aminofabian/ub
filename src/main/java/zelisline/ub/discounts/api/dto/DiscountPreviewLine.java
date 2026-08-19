package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record DiscountPreviewLine(
        String itemId,
        String itemName,
        BigDecimal regularPrice,
        BigDecimal finalPrice,
        BigDecimal savedAmount
) {
}
