package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachMarketplaceSupplierFromSeedRequest(
        @NotBlank @Size(max = 36) String sourceLocalSupplierId
) {
}
