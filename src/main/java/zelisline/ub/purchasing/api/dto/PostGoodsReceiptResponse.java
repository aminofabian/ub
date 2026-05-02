package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PostGoodsReceiptResponse(
        String goodsReceiptId,
        BigDecimal grniAmount,
        int lineCount
) {
}
