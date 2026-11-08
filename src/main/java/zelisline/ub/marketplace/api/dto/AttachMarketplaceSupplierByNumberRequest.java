package zelisline.ub.marketplace.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachMarketplaceSupplierByNumberRequest(
        @NotBlank @Size(max = 32) String supplierNumber,
        /** Marketplace product ids to pull; null = every active product, empty = supplier only. */
        @Size(max = 5000) List<@NotBlank @Size(max = 36) String> productIds
) {
}
