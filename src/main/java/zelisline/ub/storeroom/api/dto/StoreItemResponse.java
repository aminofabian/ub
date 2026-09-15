package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One store-room row.
 *
 * @param itemId            catalogue product mirrored by this row, or {@code null}
 * @param quantity          the manual count; dormant once linked + connected
 * @param inventoryQuantity live on-hand read from inventory for {@link #itemId},
 *                          or {@code null} when the row is not linked
 * @param inventoryItemName the linked product's name, so a renamed row still shows
 *                          what it is tracking
 */
public record StoreItemResponse(
        String id,
        String name,
        String barcode,
        String itemId,
        int quantity,
        LocalDate expiryDate,
        BigDecimal buyingPrice,
        BigDecimal inventoryQuantity,
        String inventoryItemName,
        Instant createdAt,
        Instant updatedAt
) {
}
