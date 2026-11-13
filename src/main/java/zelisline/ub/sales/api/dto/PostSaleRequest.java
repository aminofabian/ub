package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.PositiveOrZero;

public record PostSaleRequest(
        @NotBlank String branchId,
        @NotEmpty @Valid List<PostSaleLineRequest> lines,
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        /** When set (e.g. offline POS), may be replaced by server time if skew exceeds policy. */
        Instant clientSoldAt,
        /** Required when any payment uses {@code customer_credit} (tab / AR). */
        String customerId,
        /**
         * Cash handed over by the customer (full cash sales). Used for receipt
         * "Received" / "Change". Must be &gt;= grand total when set.
         */
        @PositiveOrZero BigDecimal cashReceived,
        /**
         * Admin override: let this one sale oversell items whose stock is exhausted. The caller
         * must hold {@code inventory.write}; the flag is ignored (and the sale rejected as usual)
         * otherwise. {@code null}/false = normal stock rules.
         */
        Boolean allowOutOfStock,
        /**
         * Tips for staff who performed a service on this sale. Not part of the sale total and
         * never revenue; the payments must cover the total plus the tips.
         */
        @Valid List<PostSaleTipRequest> tips
) {
    /** Shape used before tips existed; older queued sales and internal callers still build it. */
    public PostSaleRequest(
            String branchId,
            List<PostSaleLineRequest> lines,
            List<PostSalePaymentRequest> payments,
            Instant clientSoldAt,
            String customerId,
            BigDecimal cashReceived,
            Boolean allowOutOfStock
    ) {
        this(branchId, lines, payments, clientSoldAt, customerId, cashReceived, allowOutOfStock, null);
    }
}
