package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

public record BulkPricePreviewRow(
        String id,
        String name,
        BigDecimal currentBuying,
        BigDecimal newBuying,
        BigDecimal currentSelling,
        BigDecimal newSelling,
        boolean buyingChanged,
        boolean sellingChanged,
        boolean skippedExisting,
        boolean loss,
        boolean lowMargin
) {
}
