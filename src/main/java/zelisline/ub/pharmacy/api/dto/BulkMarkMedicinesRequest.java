package zelisline.ub.pharmacy.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Marks many items as medicines at once: an explicit list, every item in a department
 * ({@code itemTypeId}), or both. New rows start UNCLASSIFIED unless {@code dispensingClass} is given.
 */
public record BulkMarkMedicinesRequest(
        @Size(max = 500) List<@NotBlank @Size(max = 36) String> itemIds,
        @Size(max = 36) String itemTypeId,
        @Size(max = 24) String dispensingClass
) {
}
