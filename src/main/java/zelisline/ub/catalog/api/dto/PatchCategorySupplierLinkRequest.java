package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.NotNull;

public record PatchCategorySupplierLinkRequest(@NotNull Boolean primary) {
}
