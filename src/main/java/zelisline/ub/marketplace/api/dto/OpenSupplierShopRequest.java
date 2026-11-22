package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Size;

/** {@code shopAddress} is the part before the platform domain; blank uses the supplier's username. */
public record OpenSupplierShopRequest(
        @Size(max = 63) String shopAddress
) {
}
