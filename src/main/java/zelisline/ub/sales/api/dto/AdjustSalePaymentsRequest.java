package zelisline.ub.sales.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record AdjustSalePaymentsRequest(
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        @Size(max = 500) String reason
) {
}
