package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Size;

public record ReviewMarketplaceProductEditRequest(
        @Size(max = 1000) String note
) {
}
