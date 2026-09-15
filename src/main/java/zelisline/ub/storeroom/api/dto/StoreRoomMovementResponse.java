package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One entry in the store-room activity trail.
 *
 * @param storeItemName  the register row's name at read time (may be null if deleted)
 * @param itemName       the linked catalogue product's name, when there is one
 * @param stockEffect    {@code none} | {@code decrease} | {@code increase}
 * @param movementId     the {@code stock_movements.id} this produced, if any
 * @param movementCount  how many ledger rows were written (a wastage can split)
 * @param createdByName  who did it, resolved for display
 */
public record StoreRoomMovementResponse(
        String id,
        String storeItemId,
        String storeItemName,
        String itemId,
        String itemName,
        String direction,
        String reason,
        String stockEffect,
        BigDecimal quantity,
        String note,
        String movementId,
        int movementCount,
        String branchId,
        Instant createdAt,
        String createdBy,
        String createdByName
) {
}
