package zelisline.ub.kplc.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicKplcSpendStatsResponse(
        BigDecimal thisMonthAmount,
        BigDecimal thisMonthUnits,
        int thisMonthCount,
        BigDecimal allTimeAmount,
        int allTimeCount,
        List<PublicKplcMonthSpendResponse> months
) {
}
