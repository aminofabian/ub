package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;

public record PublicMarketplaceProductSearchRow(
        String productId,
        String productName,
        String barcode,
        String sku,
        String categoryName,
        String imageUrl,
        String supplierId,
        String supplierName,
        String supplierType,
        BigDecimal packSize,
        String packUnit,
        BigDecimal minOrderQty,
        BigDecimal unitPrice,
        String currency,
        boolean available
) {
}
