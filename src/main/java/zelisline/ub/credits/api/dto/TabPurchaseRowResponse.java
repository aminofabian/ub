package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TabPurchaseRowResponse(
        String saleId,
        Long receiptNo,
        Instant soldAt,
        String status,
        BigDecimal creditAmount,
        BigDecimal grandTotal,
        List<TabPurchaseLineResponse> lines
) {
}
