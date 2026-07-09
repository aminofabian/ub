package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupplierPortalRespondRequest(
        @NotEmpty List<@Valid LineResponse> lines
) {
    public record LineResponse(
            @NotBlank String purchaseOrderLineId,
            @NotBlank
            @Pattern(regexp = "accepted|rejected|partially_accepted")
            String supplierLineStatus,
            BigDecimal qtyAccepted,
            @Size(max = 2000) String supplierNote
    ) {
    }
}
