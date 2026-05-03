package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategorySupplierLinkRequest(
        @NotBlank @Size(max = 36) String supplierId,
        Integer sortOrder,
        Boolean primary
) {
}
