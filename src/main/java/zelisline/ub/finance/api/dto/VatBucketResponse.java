package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One bucket of the VAT report — a day, or a calendar month, depending on the
 * requested granularity. {@code periodStart} is the bucket's first day and
 * {@code periodEnd} its last (equal for a day bucket). Buckets with no activity
 * in the range are returned zero-filled so a chart has no gaps.
 */
public record VatBucketResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal vatCollected,
        BigDecimal vatReversed,
        BigDecimal vatPayable,
        BigDecimal netRevenue
) {
}
