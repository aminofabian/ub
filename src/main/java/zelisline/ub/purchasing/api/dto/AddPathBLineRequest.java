package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AddPathBLineRequest(
        @NotBlank @Size(max = 2000) String description,
        @NotNull @Positive BigDecimal amountMoney,
        @Size(max = 36) String suggestedItemId,
        /** Optional grocery draft qty (usable units). Null for butcher-style line-total drafts. */
        @Positive BigDecimal draftQty,
        @Positive BigDecimal draftUnitCost,
        @DecimalMin("0.01") BigDecimal draftSellPrice,
        LocalDate draftExpiryDate,
        /** Saved item pack option. When set, {@code purchaseQty} is the number of packs
         *  and the server expands it to stock units + unit cost authoritatively. */
        @Size(max = 36) String packOptionId,
        /** Manufacturer's lot number; required at post time when the item is a medicine. */
        @Size(max = 64) String draftLotNumber,
        /** Purchase unit the buyer typed ("carton", "bale", "sack"…). */
        @Size(max = 32) String purchaseUnit,
        /** Number of purchase units (packs) bought. Null = legacy unit-mode line. */
        @Positive BigDecimal purchaseQty,
        /** Base items in one purchase unit. Used for inline (unsaved) custom packs. */
        @DecimalMin(value = "0.0001", message = "unitsPerPurchaseUnit must be positive") BigDecimal unitsPerPurchaseUnit,
        /** Price of one purchase unit. Optional; derived from the line total when absent. */
        @DecimalMin("0") BigDecimal packPrice
) {

    public AddPathBLineRequest(
            String description,
            BigDecimal amountMoney,
            String suggestedItemId,
            BigDecimal draftQty,
            BigDecimal draftUnitCost,
            BigDecimal draftSellPrice,
            LocalDate draftExpiryDate,
            String packOptionId
    ) {
        this(description, amountMoney, suggestedItemId, draftQty, draftUnitCost, draftSellPrice,
                draftExpiryDate, packOptionId, null, null, null, null, null);
    }

    public AddPathBLineRequest(
            String description,
            BigDecimal amountMoney,
            String suggestedItemId,
            BigDecimal draftQty,
            BigDecimal draftUnitCost,
            BigDecimal draftSellPrice,
            LocalDate draftExpiryDate,
            String packOptionId,
            String draftLotNumber
    ) {
        this(description, amountMoney, suggestedItemId, draftQty, draftUnitCost, draftSellPrice,
                draftExpiryDate, packOptionId, draftLotNumber, null, null, null, null);
    }
}
