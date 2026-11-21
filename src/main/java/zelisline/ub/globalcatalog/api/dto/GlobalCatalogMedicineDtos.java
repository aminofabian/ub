package zelisline.ub.globalcatalog.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

public final class GlobalCatalogMedicineDtos {

    private GlobalCatalogMedicineDtos() {
    }

    /** {@code medicine} is false, and every other field null, when the product is not marked. */
    public record MedicineTemplateResponse(
            String globalProductId,
            boolean medicine,
            String genericName,
            String strength,
            String dosageForm,
            String packSize,
            String manufacturer,
            String suggestedClass
    ) {
    }

    public record UpsertMedicineTemplateRequest(
            @Size(max = 255) String genericName,
            @Size(max = 64) String strength,
            @Size(max = 64) String dosageForm,
            @Size(max = 64) String packSize,
            @Size(max = 191) String manufacturer,
            @Size(max = 24) String suggestedClass
    ) {
    }

    public record MedicineImportSkip(int row, String reason) {
    }

    /** {@code dryRun} true means nothing was written and the counts say what would happen. */
    public record MedicineImportResponse(
            boolean dryRun,
            int rows,
            int created,
            int updated,
            int unchanged,
            List<MedicineImportSkip> skipped
    ) {
    }
}
