package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A cash payment or drawout on the shift, and whether a drawer opening explains it.
 */
public record CashDrawerOpenLogTender(
        String id,
        String kind,
        String label,
        BigDecimal amount,
        Instant at,
        boolean coincided,
        Instant drawerOpenedAt
) {}
