package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;

public record GroceryDraftLineResponse(
        String id,
        int lineIndex,
        String itemId,
        String itemName,
        String itemBarcode,
        BigDecimal quantity,
        String unitName,
        BigDecimal unitPrice,
        BigDecimal discountAmount,
        BigDecimal lineTotal
) {
}
