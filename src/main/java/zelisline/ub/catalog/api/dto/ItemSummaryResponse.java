package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

public record ItemSummaryResponse(
        String id,
        String sku,
        String barcode,
        String name,
        String variantName,
        String categoryId,
        String categoryName,
        String imageKey,
        /** HTTPS URL for list thumbnails; first gallery image when {@code imageKey} is not a URL. */
        String thumbnailUrl,
        boolean active,
        boolean webPublished,
        String variantOfItemId,
        /**
         * When true, this row is a non-sellable parent that only groups variant SKUs
         * (not a sellable or stock-holding line on its own).
         * Always false for variant rows, standalone products, and sellable/stocked bases
         * that have package or option children (e.g. Eggs).
         */
        boolean groupLabelOnly,
        /**
         * On-hand quantity at the branch when {@code branchId} was passed to the list endpoint; otherwise null.
         * For package variants this is available whole packages; see {@link #baseStockQty}.
         */
        BigDecimal stockQty,
        /** When true, this SKU sells as a package and stock is held on the parent item. */
        boolean packageVariant,
        /** Base units consumed per one package sold (e.g. 30 eggs per tray). */
        BigDecimal packageUnitsPerSale,
        /**
         * Parent/base on-hand in base units when {@code packageVariant} and branch stock was requested.
         */
        BigDecimal baseStockQty,
        String brand,
        String size,
        /** Catalog shelf / bundle price on the item record (POS may use branch selling price). */
        BigDecimal bundlePrice,
        /** Department / item type ID. */
        String itemTypeId,
        /** True when the item is sold by weight (kg, g, lb). */
        boolean isWeighed,
        /** Unit of measure: each, kg, g, lb, etc. */
        String unitType
) {
}
