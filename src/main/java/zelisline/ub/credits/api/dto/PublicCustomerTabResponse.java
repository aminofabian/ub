package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicCustomerTabResponse(
        String customerName,
        String phoneDisplay,
        String shopName,
        String currency,
        BigDecimal balanceOwed,
        BigDecimal walletBalance,
        List<TabPurchaseRowResponse> purchases,
        boolean pageSealed,
        boolean pageUnlocked,
        /**
         * True when the balance and purchases were withheld: the page has no PIN yet, or it has one
         * and this viewer has not entered it. Whoever holds the phone sets the PIN with a texted code.
         */
        boolean restricted
) {
}
