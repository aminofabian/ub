package zelisline.ub.kplc.api.dto;

import java.math.BigDecimal;

public record PublicKplcMonthSpendResponse(
        String yearMonth,
        String label,
        BigDecimal amount,
        BigDecimal units,
        int tokenCount
) {
}
