package zelisline.ub.posdraft.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import zelisline.ub.sales.api.dto.PostSalePaymentRequest;
import zelisline.ub.sales.api.dto.SaleResponse;

public record CompletePosDraftRequest(
        @NotEmpty @Valid List<PostSalePaymentRequest> payments,
        String customerId,
        Instant clientSoldAt,
        Long expectedVersion
) {
}
