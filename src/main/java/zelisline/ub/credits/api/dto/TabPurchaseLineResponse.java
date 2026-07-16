package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record TabPurchaseLineResponse(
        String itemName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
