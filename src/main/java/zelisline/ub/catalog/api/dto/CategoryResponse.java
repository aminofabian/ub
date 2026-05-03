package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record CategoryResponse(
        String id,
        String name,
        String slug,
        int position,
        String icon,
        String parentId,
        boolean active,
        String description,
        BigDecimal defaultMarkupPct,
        String defaultTaxRateId,
        TaxRateSummaryResponse defaultTaxRate,
        /** Cover image URL or legacy key (often HTTPS from Cloudinary). */
        String imageKey,
        /** Resolved HTTPS URL for lists / kiosk (cover or first gallery image). */
        String thumbnailUrl,
        List<CategorySupplierSummaryResponse> linkedSuppliers
) {
}
