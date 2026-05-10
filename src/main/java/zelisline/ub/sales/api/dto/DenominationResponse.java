package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

/**
 * Denomination count returned in shift detail responses.
 */
public record DenominationResponse(
        String id,
        String countType,
        int denomination,
        String denominationType,
        int quantity,
        BigDecimal total
) {
}
