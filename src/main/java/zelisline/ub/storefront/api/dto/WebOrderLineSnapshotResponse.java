package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

public record WebOrderLineSnapshotResponse(
        String itemId,
        String itemName,
        String variantName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        int lineIndex
) {}
