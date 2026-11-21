package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PathBLineResponse(
        String id,
        int sortOrder,
        String descriptionText,
        BigDecimal amountMoney,
        String suggestedItemId,
        String lineStatus,
        BigDecimal draftQty,
        BigDecimal draftUnitCost,
        BigDecimal draftSellPrice,
        LocalDate draftExpiryDate,
        String packOptionId,
        String draftLotNumber,
        /** Packaging as entered, so reopening the draft shows packs rather than pieces. */
        String purchaseUnit,
        BigDecimal purchaseQty,
        BigDecimal unitsPerPurchaseUnit,
        BigDecimal packPrice
) {

    public PathBLineResponse(
            String id,
            int sortOrder,
            String descriptionText,
            BigDecimal amountMoney,
            String suggestedItemId,
            String lineStatus,
            BigDecimal draftQty,
            BigDecimal draftUnitCost,
            BigDecimal draftSellPrice,
            LocalDate draftExpiryDate,
            String packOptionId
    ) {
        this(id, sortOrder, descriptionText, amountMoney, suggestedItemId, lineStatus, draftQty,
                draftUnitCost, draftSellPrice, draftExpiryDate, packOptionId, null,
                null, null, null, null);
    }

    public PathBLineResponse(
            String id,
            int sortOrder,
            String descriptionText,
            BigDecimal amountMoney,
            String suggestedItemId,
            String lineStatus,
            BigDecimal draftQty,
            BigDecimal draftUnitCost,
            BigDecimal draftSellPrice,
            LocalDate draftExpiryDate,
            String packOptionId,
            String draftLotNumber
    ) {
        this(id, sortOrder, descriptionText, amountMoney, suggestedItemId, lineStatus, draftQty,
                draftUnitCost, draftSellPrice, draftExpiryDate, packOptionId, draftLotNumber,
                null, null, null, null);
    }
}
