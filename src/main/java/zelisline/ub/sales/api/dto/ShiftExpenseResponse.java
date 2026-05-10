package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A paid-in, paid-out, or safe-drop entry for a shift.
 */
public record ShiftExpenseResponse(
        String id,
        String type,
        BigDecimal amount,
        String description,
        String authorisedBy,
        String authorisedByName,
        Instant createdAt
) {
}
