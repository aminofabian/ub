package zelisline.ub.globalcatalog.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class SuperAdminGlobalCatalogDtos {

    private SuperAdminGlobalCatalogDtos() {
    }

    public record CatalogSummaryResponse(
            String id,
            String code,
            String name,
            String regionCode,
            String currency,
            String status,
            int version
    ) {
    }

    public record MetaResponse(
            String catalogId,
            String catalogCode,
            String catalogName,
            String regionCode,
            String currency,
            long productCount,
            long missingImageCount,
            long draftCount,
            long publishedCount,
            long archivedCount,
            List<CategoryResponse> categories,
            List<PackSummaryResponse> packs
    ) {
    }

    public record CategoryResponse(
            String id,
            String parentId,
            String name,
            String slug,
            String tenantCategorySlugHint,
            int position,
            boolean active
    ) {
    }

    public record PackSummaryResponse(
            String id,
            String code,
            String name,
            String description,
            String storeKitId,
            String status,
            int sortOrder,
            int productCount,
            int imagedProductCount
    ) {
    }

    public record PackDetailResponse(
            String id,
            String code,
            String name,
            String description,
            String storeKitId,
            String status,
            int sortOrder,
            List<String> productIds
    ) {
    }

    public record ProductResponse(
            String id,
            String catalogId,
            String globalCategoryId,
            String skuTemplate,
            String name,
            String brand,
            String size,
            String description,
            String barcode,
            String unitType,
            boolean weighed,
            boolean sellable,
            boolean stocked,
            BigDecimal recommendedBuyingPrice,
            BigDecimal recommendedSellingPrice,
            BigDecimal suggestedMarginPct,
            BigDecimal defaultReorderLevel,
            BigDecimal defaultReorderQty,
            BigDecimal defaultMinStockLevel,
            boolean hasExpiry,
            Integer expiresAfterDays,
            String imageUrl,
            String imagePublicId,
            String itemTypeKeyHint,
            String status,
            int sortOrder,
            long version,
            boolean barcodeDuplicateWarning
    ) {
    }

    public record CreateProductRequest(
            @NotBlank @Size(max = 500) String name,
            @Size(max = 16) String status,
            @Size(max = 16) String unitType,
            Boolean sellable,
            Boolean stocked,
            Boolean weighed,
            @Size(max = 191) String barcode,
            @Size(max = 191) String skuTemplate,
            @Size(max = 255) String brand,
            @Size(max = 50) String size,
            String description,
            String globalCategoryId,
            @Size(max = 64) String itemTypeKeyHint,
            Integer sortOrder,
            BigDecimal recommendedBuyingPrice,
            BigDecimal recommendedSellingPrice,
            BigDecimal suggestedMarginPct,
            BigDecimal defaultMinStockLevel,
            BigDecimal defaultReorderLevel,
            BigDecimal defaultReorderQty,
            Boolean hasExpiry,
            Integer expiresAfterDays
    ) {
    }

    public record PatchProductRequest(
            @NotNull Long version,
            @Size(max = 500) String name,
            @Size(max = 16) String status,
            @Size(max = 16) String unitType,
            Boolean sellable,
            Boolean stocked,
            Boolean weighed,
            @Size(max = 191) String barcode,
            @Size(max = 191) String skuTemplate,
            @Size(max = 255) String brand,
            @Size(max = 50) String size,
            String description,
            String globalCategoryId,
            @Size(max = 64) String itemTypeKeyHint,
            Integer sortOrder,
            BigDecimal recommendedBuyingPrice,
            BigDecimal recommendedSellingPrice,
            BigDecimal suggestedMarginPct,
            BigDecimal defaultMinStockLevel,
            BigDecimal defaultReorderLevel,
            BigDecimal defaultReorderQty,
            Boolean hasExpiry,
            Integer expiresAfterDays
    ) {
    }

    public record PublishProductsRequest(
            @NotEmpty List<@NotBlank String> ids
    ) {
    }

    public record PublishProductsResponse(
            int publishedCount,
            List<String> publishedIds,
            List<String> skippedIds
    ) {
    }

    public record ApplyMarginRequest(
            @NotEmpty List<@NotBlank String> ids,
            @NotNull BigDecimal marginPct,
            /** {@code fromBuying} (default) or {@code fromSelling}. */
            @Size(max = 16) String mode
    ) {
    }

    public record ApplyMarginResponse(
            int updatedCount,
            int skippedCount,
            List<String> updatedIds,
            List<String> skippedIds
    ) {
    }

    public record UpsertCategoryRequest(
            String parentId,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 191) String slug,
            @Size(max = 191) String tenantCategorySlugHint,
            Integer position,
            Boolean active
    ) {
    }

    public record BackfillImagesRequest(
            /** When empty/null, backfill the single product path id. */
            List<@NotBlank String> productIds,
            /** Max tenant items to touch per request (default 100, max 500). */
            Integer limit
    ) {
    }

    public record BackfillImagesResponse(
            int productsProcessed,
            int itemsUpdated,
            int itemsSkipped,
            int itemsFailed,
            List<String> warnings
    ) {
    }

    public record PatchPackRequest(
            @Size(max = 255) String name,
            String description,
            @Size(max = 64) String storeKitId,
            @Size(max = 16) String status,
            Integer sortOrder,
            List<@NotBlank String> productIds
    ) {
    }

    public record CsvImportResponse(
            int createdCount,
            int updatedCount,
            int skippedCount,
            List<String> warnings
    ) {
    }

    public record PromoteRequest(
            @NotBlank String sourceBusinessId,
            @NotEmpty List<@NotBlank String> itemIds,
            /** {@code update} (default) or {@code skip}. */
            String onConflict,
            Boolean publish,
            /** Target catalog id or code; omit → {@code default}. */
            String catalogId
    ) {
    }

    public record PromoteLineResult(
            String sourceItemId,
            String globalProductId,
            String action,
            String reason,
            boolean imageRehosted
    ) {
    }

    public record PromoteResponse(
            int createdCount,
            int updatedCount,
            int skippedCount,
            int imageRehostCount,
            List<PromoteLineResult> lines
    ) {
    }

    public record SourceBusinessResponse(
            String id,
            String name,
            String slug,
            boolean preferred
    ) {
    }

    public record SourceItemResponse(
            String id,
            String sku,
            String name,
            String brand,
            String size,
            String barcode,
            String imageUrl,
            boolean alreadyInGlobal,
            String matchedGlobalProductId
    ) {
    }

    public record SupplierTemplateResponse(
            String id,
            String catalogId,
            String code,
            String name,
            String supplierType,
            String vatPin,
            String notes,
            String tenantSupplierCodeHint
    ) {
    }

    public record CreateSupplierTemplateRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 255) String name,
            @Size(max = 32) String supplierType,
            @Size(max = 64) String vatPin,
            String notes
    ) {
    }

    public record PatchSupplierTemplateRequest(
            @Size(max = 255) String name,
            @Size(max = 32) String supplierType,
            @Size(max = 64) String vatPin,
            String notes
    ) {
    }

    public record ProductSupplierLinkResponse(
            String globalProductId,
            String globalSupplierTemplateId,
            String templateCode,
            String templateName,
            boolean primary,
            BigDecimal defaultCostPrice,
            String supplierSku
    ) {
    }

    public record UpsertProductSupplierLinkRequest(
            @NotBlank String globalSupplierTemplateId,
            Boolean primary,
            BigDecimal defaultCostPrice,
            @Size(max = 191) String supplierSku
    ) {
    }
}
