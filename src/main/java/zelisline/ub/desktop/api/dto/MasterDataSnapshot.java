package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Compact master-data snapshot the desktop connect flow pulls from the cloud
 * (DESKTOP_INSTALLATION.md §9b). One consistent pull instead of many paginated
 * list endpoints, so a till can seed its local MariaDB from a single call.
 *
 * <p>Only the fields a till needs to sell are included: catalog, prices, stock
 * levels, tax rates, branches and business settings. Sales history, customers
 * and media are deliberately out of scope for v1 sync.
 */
public record MasterDataSnapshot(
        BusinessData business,
        List<BranchData> branches,
        List<CategoryData> categories,
        List<ItemData> items,
        List<TaxRateData> taxRates
) {

    public record BusinessData(
            String id,
            String name,
            String slug,
            String currency,
            String countryCode,
            String timezone,
            String settings
    ) {}

    public record BranchData(
            String id,
            String name,
            String address,
            String receiptSettings,
            boolean active
    ) {}

    public record CategoryData(
            String id,
            String name,
            String slug,
            String description,
            String parentId,
            int position,
            String defaultTaxRateId,
            BigDecimal defaultMarkupPct,
            boolean active
    ) {}

    public record ItemData(
            String id,
            String sku,
            String barcode,
            String pluCode,
            String name,
            String description,
            String categoryId,
            String unitType,
            boolean stocked,
            BigDecimal currentStock,
            String packagingUnitName,
            BigDecimal packagingUnitQty,
            BigDecimal bundlePrice,
            BigDecimal buyingPrice,
            BigDecimal minStockLevel,
            String variantOfItemId,
            String variantName,
            boolean active
    ) {}

    public record TaxRateData(
            String id,
            String name,
            BigDecimal ratePercent,
            boolean inclusive,
            boolean active
    ) {}
}
