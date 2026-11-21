package zelisline.ub.pharmacy.api.dto;

import java.time.Instant;

public record MedicineDetailsResponse(
        String itemId,
        String name,
        String sku,
        String barcode,
        String itemTypeId,
        String genericName,
        String strength,
        String dosageForm,
        String packSize,
        String manufacturer,
        String registrationNo,
        String storageNote,
        String dispensingClass,
        boolean restockOnRefund,
        Instant classSetAt,
        /** Catalogue suggestion awaiting the pharmacist's confirmation; null when there is none. */
        String suggestedClass
) {
}
