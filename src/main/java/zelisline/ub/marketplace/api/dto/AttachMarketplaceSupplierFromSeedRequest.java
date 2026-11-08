package zelisline.ub.marketplace.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AttachMarketplaceSupplierFromSeedRequest(
        @NotBlank @Size(max = 36) String sourceLocalSupplierId,
        /**
         * Ids of the seed shop's supplier-product links to pull (the {@code products[].id} values of
         * the public supplier detail); null = every active product, empty = supplier only.
         */
        @Size(max = 5000) List<@NotBlank @Size(max = 36) String> productIds
) {
}
