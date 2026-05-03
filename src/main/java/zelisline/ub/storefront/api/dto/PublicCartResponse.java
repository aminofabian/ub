package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCartResponse(
        String id,
        String currency,
        String catalogBranchId,
        String catalogBranchName,
        Instant expiresAt,
        BigDecimal subtotal,
        List<PublicCartLineResponse> lines
) {
}
