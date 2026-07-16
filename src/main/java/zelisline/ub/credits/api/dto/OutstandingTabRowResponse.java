package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record OutstandingTabRowResponse(
        String customerId,
        String name,
        String primaryPhone,
        BigDecimal balanceOwed
) {
}
