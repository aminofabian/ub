package zelisline.ub.pharmacy.api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public final class TillMedicineDtos {

    private TillMedicineDtos() {
    }

    public record TillMedicinesRequest(
            @NotEmpty @Size(max = 200) List<@NotBlank @Size(max = 36) String> itemIds,
            @Size(max = 36) String branchId
    ) {
    }

    /**
     * What the till shows for one medicine on the shelf or in the cart. {@code status} is one of
     * {@code ok}, {@code needs_record}, {@code unclassified}, {@code controlled_blocked}. The lot
     * and expiry are those of the batch the next unit would come from; both are null when the
     * branch holds no sellable batch.
     */
    public record TillMedicine(
            String itemId,
            String dispensingClass,
            String status,
            String genericName,
            String strength,
            String dosageForm,
            String nextLot,
            LocalDate nextExpiry,
            boolean shortDated
    ) {
    }

    public record TillMedicinesResponse(
            List<TillMedicine> medicines,
            /** True when the caller holds {@code pharmacy.dispense}. */
            boolean mayDispense
    ) {
    }

    /** A medicine whose generic name matched what the cashier typed. */
    public record GenericMatch(String itemId, String name, String sku, String barcode, String genericName) {
    }
}
