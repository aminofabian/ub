package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;

public record PosDraftLineResponse(
        String id,
        int lineIndex,
        String itemId,
        String itemName,
        String itemBarcode,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal discountAmount,
        BigDecimal lineTotal
) {
}
