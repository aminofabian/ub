package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ApAgingTotalsResponse(
        LocalDate asOf,
        ApAgingBuckets buckets,
        BigDecimal totalOpen,
        BigDecimal totalSupplierPrepaymentBalance
) {
}
