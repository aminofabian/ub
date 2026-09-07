package zelisline.ub.catalog.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create a non-sellable family parent and attach existing standalone products as its variants.
 * Child item ids / SKUs / stock / history are preserved.
 */
public record CreateGroupFromItemsRequest(
        @NotBlank @Size(max = 500) String name,
        @NotNull @Size(max = 36) String itemTypeId,
        @Size(max = 36) String categoryId,
        @Size(max = 36) String aisleId,
        @NotEmpty @Size(max = 200) @Valid List<AttachVariantLineRequest> items
) {
}
