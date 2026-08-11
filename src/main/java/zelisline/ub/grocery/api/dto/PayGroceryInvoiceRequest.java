package zelisline.ub.grocery.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;

public record PayGroceryInvoiceRequest(
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        /** Optional; required when any payment uses customer_credit / wallet / loyalty. */
        String customerId,
        /**
         * Optional till lines added on top of the forwarded invoice (same checkout).
         * Included in the resulting sale; payments must cover invoice + these lines.
         */
        @Valid List<PostSaleLineRequest> additionalLines
) {
}
