package zelisline.ub.grocery.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;

public record PayGroceryInvoiceRequest(
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        /** Optional; required when any payment uses customer_credit / wallet / loyalty. */
        String customerId
) {
}
