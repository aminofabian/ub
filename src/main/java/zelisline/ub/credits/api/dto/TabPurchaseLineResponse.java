package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record TabPurchaseLineResponse(
        String itemName,
        String itemSku,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
