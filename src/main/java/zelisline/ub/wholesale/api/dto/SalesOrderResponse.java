package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** A trade order as the supplier's shop sees it. {@code lines} is absent in a list. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalesOrderResponse(
        String id,
        String code,
        String status,
        String currency,
        BigDecimal total,
        String buyerName,
        String buyerPhone,
        String buyerEmail,
        String buyerBusinessName,
        String notes,
        Instant createdAt,
        List<SalesOrderLineResponse> lines,
        /** {@code prepaid}, or {@code credit} when the buyer's number has an approved account here. */
        String paymentTerms,
        /** True when a signed-in marketplace buyer placed it for a shop they are staff of. */
        boolean buyerVerified,
        /** True when this order took the buyer's account over its credit limit. */
        boolean overCreditLimit
) {
}
