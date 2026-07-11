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
        @PositiveOrZero BigDecimal cashReceived
) {
}
