package zelisline.ub.sales.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record PostSaleRequest(
        @NotBlank String branchId,
        @NotEmpty @Valid List<PostSaleLineRequest> lines,
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        /** When set (e.g. offline POS), may be replaced by server time if skew exceeds policy. */
        Instant clientSoldAt
) {
}
