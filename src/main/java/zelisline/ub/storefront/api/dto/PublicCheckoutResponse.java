package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PublicCheckoutResponse(
        String orderId,
        /** Canonical short order code (scope D11) — what the WhatsApp message quotes. */
        String orderCode,
        String status,
        BigDecimal grandTotal,
        String currency,
        String catalogBranchName,
        Instant createdAt,
        /** Phase 5: one-tap receipt token — append to the tracking link (`?t=`), single-use. */
        String receiptToken
) {}
