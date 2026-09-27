package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Completed sales grouped into business-local clock hours (7:00–8:00, 8:00–9:00, …). */
public record SalesByHourResponse(
        String timezone,
        int saleCount,
        BigDecimal revenue,
        List<SalesHourRow> hours
) {
    public record SalesHourRow(
            /** Hour of day the window starts, 0–23. The window is {@code [hour, hour+1)}. */
            int hour,
            int saleCount,
            BigDecimal revenue,
            /** Sales in this hour that were left off {@link #sales} so the payload stays readable. */
            int omitted,
            List<SalesHourSale> sales
    ) {
    }

    public record SalesHourSale(
            String saleId,
            Long receiptNo,
            Instant soldAt,
            String cashierName,
            String paymentMethod,
            BigDecimal total
    ) {
    }
}
