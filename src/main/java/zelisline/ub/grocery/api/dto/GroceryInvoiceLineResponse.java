package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;

public record GroceryInvoiceLineResponse(
        String id,
        String itemId,
        String itemName,
        int lineIndex,
        BigDecimal quantity,
        String unitName,
        BigDecimal unitPrice,
        BigDecimal lineTotal
) {
}
