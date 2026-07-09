package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicMarketplaceProductSearchRow(
        String productId,
        String productName,
        String productSlug,
        String barcode,
        String sku,
        String categoryName,
        String imageUrl,
        String supplierId,
        String supplierName,
        String supplierSlug,
        String supplierType,
        int supplierProductCount,
        String location,
        List<String> locations,
        BigDecimal packSize,
        String packUnit,
        BigDecimal minOrderQty,
        BigDecimal unitPrice,
        String currency,
        boolean available
) {
}
