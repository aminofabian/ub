package zelisline.ub.pricing.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Batch shelf-price resolution for POS tiles (discount-aware final prices).
 */
public record ResolvePricesRequest(
        @NotEmpty @Size(max = 200) List<String> itemIds,
        String branchId
) {
}
