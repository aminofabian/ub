package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record TabPurchaseRowResponse(
        String saleId,
        Long receiptNo,
        Instant soldAt,
        String status,
        /** Amount charged to the customer's tab for this sale (0 for cash/wallet visits). */
        BigDecimal creditAmount,
        BigDecimal grandTotal,
        /** Change parked on wallet from this sale, if any. */
        BigDecimal walletCredited,
        List<TabPurchaseLineResponse> lines
) {
}
