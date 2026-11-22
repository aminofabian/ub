package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A buyer's approved account, as the clerk at the counter needs it: the discount, the buyer's own prices by item
 * id, and how much credit is left. {@code found} is false when the phone number has no approved account.
 */
public record CounterTermsResponse(
        boolean found,
        String accountId,
        String buyerName,
        String businessName,
        BigDecimal discountPercent,
        Integer termsDays,
        BigDecimal creditLimit,
        BigDecimal creditAvailable,
        Map<String, BigDecimal> prices
) {

    public static CounterTermsResponse none() {
        return new CounterTermsResponse(false, null, null, null, null, null, null, null, Map.of());
    }
}
