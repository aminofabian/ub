package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ApAgingBuckets(
        BigDecimal current,
        BigDecimal days1To30,
        BigDecimal days31To60,
        BigDecimal days61To90,
        BigDecimal daysOver90
) {
}
