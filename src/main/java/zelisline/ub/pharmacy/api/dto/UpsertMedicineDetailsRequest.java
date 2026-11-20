package zelisline.ub.pharmacy.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Marks an item as a medicine (first call) or updates its details. {@code dispensingClass} is
 * optional: when absent a new row starts as UNCLASSIFIED and an existing row keeps its class.
 */
public record UpsertMedicineDetailsRequest(
        @Size(max = 255) String genericName,
        @Size(max = 64) String strength,
        @Size(max = 64) String dosageForm,
        @Size(max = 64) String packSize,
        @Size(max = 191) String manufacturer,
        @Size(max = 64) String registrationNo,
        @Size(max = 255) String storageNote,
        @Size(max = 24) String dispensingClass,
        Boolean restockOnRefund
) {
}
