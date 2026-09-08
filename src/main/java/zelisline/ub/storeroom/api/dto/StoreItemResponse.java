package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record StoreItemResponse(
        String id,
        String name,
        String barcode,
        int quantity,
        LocalDate expiryDate,
        BigDecimal buyingPrice,
        Instant createdAt,
        Instant updatedAt
) {
}
