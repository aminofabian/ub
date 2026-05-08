package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record PaymentMethodBreakdownRow(
        String method,
        long transactionCount,
        BigDecimal totalAmount
) {
}
