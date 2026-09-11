package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Capture health (warehouse scope §8.5): how much of the till's completed
 * volume is named. Overall linked share plus the cash / M-Pesa / tab split so
 * the owner can see which tender is starving the warehouse.
 */
public record CaptureHealthResponse(
        LocalDate from,
        LocalDate to,
        long totalSales,
        long identifiedSales,
        BigDecimal identifiedPct,
        List<TenderSplit> tenders
) {

    public record TenderSplit(
            String tender,
            long totalSales,
            long identifiedSales,
            BigDecimal identifiedPct
    ) {
    }
}
