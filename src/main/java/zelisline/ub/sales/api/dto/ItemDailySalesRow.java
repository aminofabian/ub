package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ItemDailySalesRow(
        LocalDate day,
        BigDecimal qty,
        BigDecimal revenue,
        BigDecimal cost,
        BigDecimal profit
) {
}
