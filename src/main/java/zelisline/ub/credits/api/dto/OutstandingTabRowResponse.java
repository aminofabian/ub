package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record OutstandingTabRowResponse(
        String customerId,
        String name,
        String primaryPhone,
        BigDecimal balanceOwed,
        boolean creditSuspended,
        /** A number on this customer is verified — by a person, or by their own code. */
        boolean verified,
        /** The number a manual verification would mark, when the customer has one. */
        String primaryPhoneId
) {
}
