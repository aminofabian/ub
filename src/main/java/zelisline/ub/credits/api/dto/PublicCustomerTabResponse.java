package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicCustomerTabResponse(
        String customerName,
        String phoneDisplay,
        String shopName,
        String currency,
        BigDecimal balanceOwed,
        List<TabPurchaseRowResponse> purchases
) {
}
