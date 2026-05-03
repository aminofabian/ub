package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCheckoutResponse(
        String orderId,
        String status,
        BigDecimal grandTotal,
        String currency,
        String catalogBranchName,
        Instant createdAt
) {}
