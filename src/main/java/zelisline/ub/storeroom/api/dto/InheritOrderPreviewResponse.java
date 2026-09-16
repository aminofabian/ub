package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * A purchase order as the store room would put it in: remaining qty, matched
 * register rows, and whether this PO was already inherited.
 */
public record InheritOrderPreviewResponse(
        String purchaseOrderId,
        String poNumber,
        String status,
        String deliveryStatus,
        String supplierId,
        String branchId,
        LocalDate expectedDate,
        Instant createdAt,
        boolean alreadyInherited,
        boolean unpacked,
        List<Line> lines
) {
    public record Line(
            String purchaseOrderLineId,
            String itemId,
            String itemName,
            String barcode,
            BigDecimal qtyOrdered,
            BigDecimal qtyReceived,
            BigDecimal remaining,
            BigDecimal displayToHolderFactor,
            String catalogPackUnit,
            String storeItemId,
            boolean onList
    ) {
    }
}
