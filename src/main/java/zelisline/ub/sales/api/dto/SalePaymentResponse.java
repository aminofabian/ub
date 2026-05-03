package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record SalePaymentResponse(String method, BigDecimal amount, String reference) {
}
