package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachMarketplaceSupplierByNumberRequest(
        @NotBlank @Size(max = 32) String supplierNumber
) {
}
