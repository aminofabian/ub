package zelisline.ub.support.api.dto;

import java.math.BigDecimal;
import java.util.List;

/** Structured payload for {@code ORDER_CARD} support messages. */
public record SupportOrderCardDto(
        String orderId,
        String orderCode,
        String status,
        String currency,
        BigDecimal grandTotal,
        String customerName,
        String customerPhone,
        String branchName,
        String channel,
        List<Line> lines,
        int lineCount
) {
    public record Line(
            String itemName,
            String variantName,
            BigDecimal quantity,
            BigDecimal lineTotal
    ) {}
}
