package zelisline.ub.marketplace.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Optional body for {@code POST /api/v1/marketplace/suppliers/{marketplaceSupplierId}/attach}.
 *
 * <p>{@code productIds} chooses which of the supplier's shelf to pull into the shop. Omit the body
 * (or send {@code null}) to pull every active product; an empty list attaches the supplier only;
 * otherwise only the listed marketplace product ids are imported and linked.
 */
public record AttachMarketplaceSupplierRequest(
        @Size(max = 5000) List<@NotBlank @Size(max = 36) String> productIds
) {
}
