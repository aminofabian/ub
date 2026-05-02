package zelisline.ub.purchasing.api.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record PostGoodsReceiptRequest(
        @NotBlank String purchaseOrderId,
        @NotBlank String branchId,
        @NotNull Instant receivedAt,
        String notes,
        @NotEmpty @Valid List<PostGoodsReceiptLineInput> lines
) {
}
