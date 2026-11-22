package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TradeAccountResponse(
        String id,
        String status,
        String buyerName,
        String buyerPhone,
        String businessName,
        String note,
        BigDecimal creditLimit,
        Integer termsDays,
        Instant createdAt,
        /** True when the account belongs to a shop whose staff signed in, not just a phone number. */
        boolean buyerVerified,
        BigDecimal discountPercent
) {
}
