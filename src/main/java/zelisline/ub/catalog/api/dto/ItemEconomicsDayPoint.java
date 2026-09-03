package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ItemEconomicsDayPoint(
        LocalDate date,
        BigDecimal unitsSold,
        BigDecimal revenue
) {
}
