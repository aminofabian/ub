package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import zelisline.ub.catalog.domain.ItemPackOption;

public record ItemPackOptionResponse(
        String id,
        String label,
        String packUnit,
        BigDecimal unitsPerPack,
        BigDecimal defaultPackPrice,
        String barcode,
        String skuSuffix,
        int sortOrder,
        boolean active,
        Instant createdAt,
        Instant updatedAt
) {
    public static ItemPackOptionResponse from(ItemPackOption option) {
        return new ItemPackOptionResponse(
                option.getId(),
                option.getLabel(),
                option.getPackUnit(),
                option.getUnitsPerPack(),
                option.getDefaultPackPrice(),
                option.getBarcode(),
                option.getSkuSuffix(),
                option.getSortOrder(),
                option.isActive(),
                option.getCreatedAt(),
                option.getUpdatedAt());
    }
}
