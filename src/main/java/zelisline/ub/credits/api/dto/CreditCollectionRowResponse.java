package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CreditCollectionRowResponse(
        String id,
        String customerId,
        String name,
        Instant paidAt,
        BigDecimal amount
) {
}
