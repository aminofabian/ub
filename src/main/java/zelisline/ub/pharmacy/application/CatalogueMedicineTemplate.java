package zelisline.ub.pharmacy.application;

import zelisline.ub.pharmacy.domain.DispensingClass;

/**
 * What the platform catalogue knows about a medicine, handed over when a shop adopts the product.
 * {@code suggestedClass} may be null and is never UNCLASSIFIED.
 */
public record CatalogueMedicineTemplate(
        String genericName,
        String strength,
        String dosageForm,
        String packSize,
        String manufacturer,
        DispensingClass suggestedClass
) {
}
