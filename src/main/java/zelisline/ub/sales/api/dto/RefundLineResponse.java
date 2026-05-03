package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record RefundLineResponse(String saleItemId, BigDecimal quantity, BigDecimal amount) {
}
