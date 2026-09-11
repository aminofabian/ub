package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record TabPurchaseLineResponse(
        String itemName,
        String itemSku,
        String itemBarcode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
