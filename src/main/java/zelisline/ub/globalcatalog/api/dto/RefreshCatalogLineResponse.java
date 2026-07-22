package zelisline.ub.globalcatalog.api.dto;

import java.math.BigDecimal;

public record RefreshCatalogLineResponse(
        String globalProductId,
        String itemId,
        String status,
        String message,
        BigDecimal currentSellingPrice,
        BigDecimal recommendedSellingPrice,
        BigDecimal currentBuyingPrice,
        BigDecimal recommendedBuyingPrice,
        boolean sellingUpdated,
        boolean buyingUpdated,
        boolean imageUpdated
) {
}
