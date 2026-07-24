package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ApplyMarginRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ArchiveCatalogProductsResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CatalogSummaryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ApplyMarginResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.BackfillImagesRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.BackfillImagesResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CategoryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CreateProductRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.CreateSupplierTemplateRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.MetaResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PackDetailResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PackSummaryResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchPackRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchProductRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PatchSupplierTemplateRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ProductImageResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ProductResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.ProductSupplierLinkResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PublishProductsRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PublishProductsResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PurgeCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PurgeCatalogResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SupplierTemplateResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.UpsertCategoryRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.UpsertProductSupplierLinkRequest;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductImage;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductPackItem;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.domain.GlobalProductSupplierLink;
import zelisline.ub.globalcatalog.domain.GlobalSupplierTemplate;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackItemRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductSupplierLinkRepository;
import zelisline.ub.globalcatalog.repository.GlobalSupplierTemplateRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;

@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogService {

    private static final String DEFAULT_CATALOG_CODE = "default";
    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;
    private static final int DEFAULT_BACKFILL_LIMIT = 100;
    private static final int MAX_BACKFILL_LIMIT = 500;

    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;
    private final GlobalProductPackRepository globalProductPackRepository;
    private final GlobalProductPackItemRepository globalProductPackItemRepository;
    private final GlobalSupplierTemplateRepository globalSupplierTemplateRepository;
    private final GlobalProductSupplierLinkRepository globalProductSupplierLinkRepository;
    private final ItemRepository itemRepository;
    private final GlobalCatalogAdoptImageAttacher adoptImageAttacher;
    private final GlobalProductImageGalleryService galleryService;
    private final MediaStore mediaStore;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<CatalogSummaryResponse> listCatalogs() {
        return globalCatalogRepository.findAllByOrderByCodeAsc().stream()
                .map(c -> new CatalogSummaryResponse(
                        c.getId(),
                        c.getCode(),
                        c.getName(),
                        c.getRegionCode(),
                        c.getCurrency(),
                        c.getStatus(),
                        c.getVersion()))
                .toList();
    }

    /**
     * Archives every non-archived product and deactivates every active category in the
     * catalog. Used by the promote flow's "replace catalog contents" option so a fresh
     * promote mirrors the source tenant exactly. Archiving (not deleting) keeps ids
     * stable for tenants that already adopted these products.
     */
    @Transactional
    public ArchiveCatalogProductsResponse archiveAllProducts(String catalogId) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String resolvedCatalogId = catalog.getId();

        List<GlobalProduct> toArchive = globalProductRepository.findAll().stream()
                .filter(p -> resolvedCatalogId.equals(p.getCatalogId()))
                .filter(p -> !GlobalProductStatus.ARCHIVED.equals(p.getStatus()))
                .toList();
        if (!toArchive.isEmpty()) {
            // Drop pack memberships so replace+promote doesn't leave ghost counts.
            globalProductPackItemRepository.deleteByGlobalProductIdIn(
                    toArchive.stream().map(GlobalProduct::getId).toList());
        }
        toArchive.forEach(p -> p.setStatus(GlobalProductStatus.ARCHIVED));
        globalProductRepository.saveAll(toArchive);

        List<GlobalCategory> toDeactivate = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(resolvedCatalogId);
        toDeactivate.forEach(c -> c.setActive(false));
        globalCategoryRepository.saveAll(toDeactivate);

        return new ArchiveCatalogProductsResponse(toArchive.size(), toDeactivate.size());
    }

    /**
     * Hard-deletes all products/categories/packs/images/supplier data in the catalog.
     * Keeps the catalog shell. Refuses when any tenant item still references a product
     * in this catalog (including soft-deleted items — FK has no cascade).
     */
    @Transactional
    public PurgeCatalogResponse purgeCatalog(String catalogId, PurgeCatalogRequest body) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String expectedCode = catalog.getCode();
        String confirmCode = body == null || body.confirmCode() == null ? "" : body.confirmCode().trim();
        if (!expectedCode.equals(confirmCode)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "confirmCode must match catalog code '" + expectedCode + "'");
        }

        long adopted = itemRepository.countReferencingCatalogProducts(catalog.getId());
        if (adopted > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot purge: "
                            + adopted
                            + " shop item(s) still reference products in this catalog. "
                            + "Tenant inventory is untouched; use archive-all instead, "
                            + "or clear provenance before purging.");
        }

        CatalogRegionalCloneJdbc.PurgeStats stats;
        try {
            stats = jdbcTemplate.execute(
                    (ConnectionCallback<CatalogRegionalCloneJdbc.PurgeStats>) connection ->
                            CatalogRegionalCloneJdbc.purgeCatalogContent(connection, catalog.getId()));
        } catch (DataIntegrityViolationException ex) {
            throw translatePurgeIntegrityFailure(ex);
        } catch (DataAccessException ex) {
            throw translatePurgeDataAccessFailure(ex);
        }
        if (stats == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Catalog purge failed");
        }

        return new PurgeCatalogResponse(
                catalog.getId(),
                catalog.getCode(),
                stats.deletedProductCount(),
                stats.deletedCategoryCount(),
                stats.deletedPackCount(),
                stats.deletedPackItemCount(),
                stats.deletedImageCount(),
                stats.deletedSupplierLinkCount(),
                stats.deletedSupplierTemplateCount()
        );
    }

    private static ResponseStatusException translatePurgeIntegrityFailure(DataIntegrityViolationException ex) {
        String flat = flattenThrowableMessages(ex).toLowerCase();
        if (flat.contains("fk_items_global_product_source")
                || flat.contains("global_product_source_id")
                || flat.contains("items")) {
            return new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Cannot purge: shop item(s) still reference products in this catalog. "
                            + "Tenant inventory is untouched; use archive-all instead.");
        }
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot purge catalog due to a database constraint: " + rootMessage(ex));
    }

    private static ResponseStatusException translatePurgeDataAccessFailure(DataAccessException ex) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Cannot purge catalog: " + rootMessage(ex));
    }

    private static String rootMessage(Throwable ex) {
        Throwable cursor = ex;
        while (cursor.getCause() != null && cursor.getCause() != cursor) {
            cursor = cursor.getCause();
        }
        String message = cursor.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) : message;
    }

    private static String flattenThrowableMessages(Throwable ex) {
        StringBuilder out = new StringBuilder();
        Throwable cursor = ex;
        while (cursor != null) {
            if (cursor.getMessage() != null) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }
                out.append(cursor.getMessage());
            }
            cursor = cursor.getCause();
        }
        return out.toString();
    }

    @Transactional(readOnly = true)
    public MetaResponse getMeta(String catalogId) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String resolvedCatalogId = catalog.getId();
        List<GlobalProduct> all = globalProductRepository.findAll().stream()
                .filter(p -> resolvedCatalogId.equals(p.getCatalogId()))
                .toList();
        long missingImage = all.stream().filter(p -> blankToNull(p.getImageUrl()) == null).count();
        long draft = all.stream().filter(p -> GlobalProductStatus.DRAFT.equals(p.getStatus())).count();
        long published = all.stream().filter(p -> GlobalProductStatus.PUBLISHED.equals(p.getStatus())).count();
        long archived = all.stream().filter(p -> GlobalProductStatus.ARCHIVED.equals(p.getStatus())).count();

        List<CategoryResponse> categories = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(resolvedCatalogId)
                .stream()
                .map(this::toCategory)
                .toList();

        List<PackSummaryResponse> packs = allPacksAsSummaries(resolvedCatalogId);

        return new MetaResponse(
                catalog.getId(),
                catalog.getCode(),
                catalog.getName(),
                catalog.getRegionCode(),
                catalog.getCurrency(),
                all.size(),
                missingImage,
                draft,
                published,
                archived,
                categories,
                packs
        );
    }

    @Transactional(readOnly = true)
    public Page<ProductResponse> listProducts(
            String catalogId,
            String q,
            String status,
            String categoryId,
            boolean missingImage,
            int page,
            int size
    ) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String statusFilter = blankToNull(status);
        if (statusFilter != null && !GlobalProductStatus.isAllowed(statusFilter)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid status filter");
        }
        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.asc("sortOrder"), Sort.Order.asc("name")));
        Page<GlobalProduct> result = globalProductRepository.searchForSuperAdmin(
                catalog.getId(),
                statusFilter,
                blankToNull(categoryId),
                blankToNull(q),
                missingImage,
                pageable);
        Map<String, List<GlobalProductImage>> imagesByProduct = imagesByProductId(result.getContent());
        return result.map(gp -> toProduct(gp, imagesByProduct.getOrDefault(gp.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(String id, String catalogId) {
        GlobalProduct product = requireProduct(id, catalogId);
        return toProduct(product, galleryService.listForProduct(product.getId()));
    }

    @Transactional
    public ProductResponse createProduct(String catalogId, CreateProductRequest req) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        GlobalProduct product = new GlobalProduct();
        product.setCatalogId(catalog.getId());
        applyCreateFields(product, req);
        assertBarcodeAvailable(product.getCatalogId(), product.getBarcode(), product.getStatus(), null);
        return toProduct(globalProductRepository.save(product), List.of());
    }

    @Transactional
    public ProductResponse patchProduct(String id, String catalogId, PatchProductRequest req) {
        GlobalProduct product = requireProduct(id, catalogId);
        if (req.version() != product.getVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product was modified by another editor");
        }
        applyPatchFields(product, req);
        assertBarcodeAvailable(product.getCatalogId(), product.getBarcode(), product.getStatus(), product.getId());
        try {
            GlobalProduct saved = globalProductRepository.saveAndFlush(product);
            return toProduct(saved, galleryService.listForProduct(saved.getId()));
        } catch (ObjectOptimisticLockingFailureException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Product was modified by another editor");
        }
    }

    @Transactional
    public PublishProductsResponse publishProducts(PublishProductsRequest req) {
        List<String> published = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String id : req.ids()) {
            GlobalProduct product = globalProductRepository.findById(id).orElse(null);
            if (product == null) {
                skipped.add(id);
                continue;
            }
            try {
                assertBarcodeAvailable(
                        product.getCatalogId(), product.getBarcode(), GlobalProductStatus.PUBLISHED, product.getId());
                product.setStatus(GlobalProductStatus.PUBLISHED);
                globalProductRepository.save(product);
                published.add(id);
            } catch (ResponseStatusException ex) {
                skipped.add(id);
            }
        }
        return new PublishProductsResponse(published.size(), published, skipped);
    }

    /**
     * Bulk price refresh: set suggested margin and derive the paired recommended price.
     *
     * <ul>
     *   <li>{@code fromBuying} — sell = buy × (1 + margin/100); skips rows without buying price</li>
     *   <li>{@code fromSelling} — buy = sell ÷ (1 + margin/100); skips rows without selling price</li>
     * </ul>
     */
    @Transactional
    public ApplyMarginResponse applyMargin(String catalogId, ApplyMarginRequest req) {
        BigDecimal marginPct = req.marginPct();
        if (marginPct.compareTo(BigDecimal.ZERO) < 0 || marginPct.compareTo(new BigDecimal("999.99")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "marginPct must be between 0 and 999.99");
        }
        String mode = req.mode() == null || req.mode().isBlank()
                ? "fromBuying"
                : req.mode().trim().toLowerCase(Locale.ROOT);
        if (!"frombuying".equals(mode) && !"fromselling".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be fromBuying or fromSelling");
        }
        boolean fromBuying = "frombuying".equals(mode);
        BigDecimal multiplier = BigDecimal.ONE.add(marginPct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP));

        GlobalCatalog catalog = requireCatalog(catalogId);
        List<String> updated = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (String id : req.ids()) {
            GlobalProduct product = globalProductRepository.findById(id)
                    .filter(p -> catalog.getId().equals(p.getCatalogId()))
                    .orElse(null);
            if (product == null) {
                skipped.add(id);
                continue;
            }
            if (fromBuying) {
                BigDecimal buy = product.getRecommendedBuyingPrice();
                if (buy == null || buy.compareTo(BigDecimal.ZERO) <= 0) {
                    skipped.add(id);
                    continue;
                }
                product.setRecommendedSellingPrice(buy.multiply(multiplier).setScale(2, RoundingMode.HALF_UP));
            } else {
                BigDecimal sell = product.getRecommendedSellingPrice();
                if (sell == null || sell.compareTo(BigDecimal.ZERO) <= 0 || multiplier.compareTo(BigDecimal.ZERO) == 0) {
                    skipped.add(id);
                    continue;
                }
                product.setRecommendedBuyingPrice(sell.divide(multiplier, 2, RoundingMode.HALF_UP));
            }
            product.setSuggestedMarginPct(marginPct.setScale(2, RoundingMode.HALF_UP));
            globalProductRepository.save(product);
            updated.add(id);
        }
        return new ApplyMarginResponse(updated.size(), skipped.size(), updated, skipped);
    }

    @Transactional
    public ProductResponse uploadProductImage(String id, String catalogId, MultipartFile file) {
        GlobalProduct product = requireProduct(id, catalogId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image file");
        }
        if (file.getSize() > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds size limit");
        }
        if (!mediaStore.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Image storage not configured");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read image file");
        }
        String folder = CloudinaryImageService.folderGlobalCatalog(product.getId());
        CloudinaryUploadResult uploaded = mediaStore.uploadImageToFolder(
                bytes, file.getOriginalFilename(), folder, true);
        if (blankToNull(uploaded.secureUrl()) == null || blankToNull(uploaded.publicId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Image upload returned empty result");
        }
        String previousPublicId = blankToNull(product.getImagePublicId());
        product.setImageUrl(uploaded.secureUrl());
        product.setImagePublicId(uploaded.publicId());
        GlobalProduct saved = globalProductRepository.save(product);
        galleryService.syncCoverAsPrimary(saved);
        if (previousPublicId != null && !previousPublicId.equals(uploaded.publicId())) {
            try {
                mediaStore.destroyImage(previousPublicId);
            } catch (Exception ignored) {
                // orphan acceptable for v1
            }
        }
        return toProduct(saved, galleryService.listForProduct(saved.getId()));
    }

    @Transactional
    public ProductResponse clearProductImage(String id, String catalogId) {
        GlobalProduct product = requireProduct(id, catalogId);
        String publicId = blankToNull(product.getImagePublicId());
        product.setImageUrl(null);
        product.setImagePublicId(null);
        GlobalProduct saved = globalProductRepository.save(product);
        galleryService.clearGallery(saved);
        if (publicId != null && mediaStore.isConfigured()) {
            try {
                mediaStore.destroyImage(publicId);
            } catch (Exception ignored) {
                // keep row cleared even if CDN destroy fails
            }
        }
        return toProduct(saved, List.of());
    }

    /**
     * Push a global product's HTTPS image into tenant items that already adopted it
     * but still lack a cover (shops that adopted before imaging).
     */
    @Transactional
    public BackfillImagesResponse backfillAdoptedImages(
            String productId,
            String catalogId,
            BackfillImagesRequest request
    ) {
        List<String> ids = new ArrayList<>();
        if (productId != null && !productId.isBlank()) {
            ids.add(productId.trim());
        }
        if (request != null && request.productIds() != null) {
            for (String id : request.productIds()) {
                if (id != null && !id.isBlank() && !ids.contains(id.trim())) {
                    ids.add(id.trim());
                }
            }
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide productId path or productIds body");
        }

        int limit = DEFAULT_BACKFILL_LIMIT;
        if (request != null && request.limit() != null) {
            limit = Math.min(Math.max(request.limit(), 1), MAX_BACKFILL_LIMIT);
        }

        int productsProcessed = 0;
        int updated = 0;
        int skipped = 0;
        int failed = 0;
        List<String> warnings = new ArrayList<>();
        int remaining = limit;

        for (String id : ids) {
            if (remaining <= 0) {
                break;
            }
            GlobalProduct product = requireProduct(id, catalogId);
            String imageUrl = blankToNull(product.getImageUrl());
            if (imageUrl == null || !(imageUrl.startsWith("http://") || imageUrl.startsWith("https://"))) {
                warnings.add(id + ": no portable HTTPS image_url");
                productsProcessed++;
                continue;
            }
            productsProcessed++;
            List<Item> adopted = itemRepository.findByGlobalProductSourceIdAndDeletedAtIsNull(product.getId());
            for (Item item : adopted) {
                if (remaining <= 0) {
                    break;
                }
                remaining--;
                GlobalCatalogAdoptImageAttacher.AttachResult result = adoptImageAttacher.attachFromGlobalUrl(
                        item.getBusinessId(),
                        item.getId(),
                        imageUrl,
                        true
                );
                if (result.attached()) {
                    updated++;
                } else if (result.warning() == null || result.warning().isBlank()) {
                    skipped++;
                } else if (result.warning().contains("skipped") || result.warning().contains("Cover set")) {
                    // cover-only path or already had cover
                    if (result.warning().contains("gallery registration skipped")) {
                        updated++;
                        warnings.add(item.getId() + ": " + result.warning());
                    } else {
                        skipped++;
                    }
                } else {
                    failed++;
                    warnings.add(item.getId() + ": " + result.warning());
                }
            }
        }

        return new BackfillImagesResponse(productsProcessed, updated, skipped, failed, warnings);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(String catalogId) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        return globalCategoryRepository.findAll().stream()
                .filter(c -> catalog.getId().equals(c.getCatalogId()))
                .sorted((a, b) -> Integer.compare(a.getPosition(), b.getPosition()))
                .map(this::toCategory)
                .toList();
    }

    @Transactional
    public CategoryResponse createCategory(String catalogId, UpsertCategoryRequest req) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        GlobalCategory category = new GlobalCategory();
        category.setCatalogId(catalog.getId());
        applyCategory(category, req, true);
        return toCategory(globalCategoryRepository.save(category));
    }

    @Transactional
    public CategoryResponse patchCategory(String id, String catalogId, UpsertCategoryRequest req) {
        GlobalCategory category = globalCategoryRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
        if (catalogId != null && !catalogId.isBlank()) {
            GlobalCatalog catalog = requireCatalog(catalogId);
            if (!catalog.getId().equals(category.getCatalogId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found");
            }
        }
        applyCategory(category, req, false);
        return toCategory(globalCategoryRepository.save(category));
    }

    @Transactional(readOnly = true)
    public List<PackSummaryResponse> listPacks(String catalogId) {
        return getMeta(catalogId).packs();
    }

    private List<PackSummaryResponse> allPacksAsSummaries(String catalogId) {
        return globalProductPackRepository.findAll().stream()
                .filter(p -> catalogId.equals(p.getCatalogId()))
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .map(pack -> {
                    List<String> productIds = globalProductPackRepository.findProductIdsByPackId(pack.getId());
                    int imaged = 0;
                    for (String productId : productIds) {
                        GlobalProduct gp = globalProductRepository.findById(productId).orElse(null);
                        if (gp != null && blankToNull(gp.getImageUrl()) != null) {
                            imaged++;
                        }
                    }
                    return new PackSummaryResponse(
                            pack.getId(),
                            pack.getCode(),
                            pack.getName(),
                            pack.getDescription(),
                            pack.getStoreKitId(),
                            pack.getStatus(),
                            pack.getSortOrder(),
                            productIds.size(),
                            imaged);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public PackDetailResponse getPack(String id, String catalogId) {
        GlobalProductPack pack = resolvePack(id, catalogId);
        return new PackDetailResponse(
                pack.getId(),
                pack.getCode(),
                pack.getName(),
                pack.getDescription(),
                pack.getStoreKitId(),
                pack.getStatus(),
                pack.getSortOrder(),
                globalProductPackRepository.findProductIdsByPackId(pack.getId()));
    }

    @Transactional
    public PackDetailResponse patchPack(String id, String catalogId, PatchPackRequest req) {
        GlobalProductPack pack = resolvePack(id, catalogId);
        GlobalCatalog catalog = requireCatalog(pack.getCatalogId());
        if (req.name() != null && !req.name().isBlank()) {
            pack.setName(req.name().trim());
        }
        if (req.description() != null) {
            pack.setDescription(req.description());
        }
        if (req.storeKitId() != null) {
            pack.setStoreKitId(blankToNull(req.storeKitId()));
        }
        if (req.status() != null && !req.status().isBlank()) {
            pack.setStatus(req.status().trim().toLowerCase(Locale.ROOT));
        }
        if (req.sortOrder() != null) {
            pack.setSortOrder(req.sortOrder());
        }
        globalProductPackRepository.save(pack);

        if (req.productIds() != null) {
            Set<String> unique = new HashSet<>();
            List<String> ordered = new ArrayList<>();
            for (String productId : req.productIds()) {
                if (productId == null || productId.isBlank() || !unique.add(productId)) {
                    continue;
                }
                GlobalProduct product = globalProductRepository.findById(productId)
                        .filter(p -> catalog.getId().equals(p.getCatalogId()))
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "Unknown product in pack: " + productId));
                ordered.add(product.getId());
            }
            globalProductPackItemRepository.deleteByPackId(pack.getId());
            int sort = 0;
            for (String productId : ordered) {
                GlobalProductPackItem row = new GlobalProductPackItem();
                row.setPackId(pack.getId());
                row.setGlobalProductId(productId);
                row.setSortOrder(sort++);
                globalProductPackItemRepository.save(row);
            }
        }
        return getPack(pack.getId(), pack.getCatalogId());
    }

    @Transactional(readOnly = true)
    public List<SupplierTemplateResponse> listSupplierTemplates(String catalogId) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        return globalSupplierTemplateRepository.findByCatalogIdOrderByNameAsc(catalog.getId()).stream()
                .map(this::toSupplierTemplate)
                .toList();
    }

    @Transactional
    public SupplierTemplateResponse createSupplierTemplate(String catalogId, CreateSupplierTemplateRequest req) {
        GlobalCatalog catalog = requireCatalog(catalogId);
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (globalSupplierTemplateRepository.findByCatalogIdAndCode(catalog.getId(), code).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier template code already exists");
        }
        GlobalSupplierTemplate template = new GlobalSupplierTemplate();
        template.setCatalogId(catalog.getId());
        template.setCode(code);
        template.setName(req.name().trim());
        template.setSupplierType(blankToNull(req.supplierType()) != null
                ? req.supplierType().trim().toLowerCase(Locale.ROOT)
                : "distributor");
        template.setVatPin(blankToNull(req.vatPin()));
        template.setNotes(blankToNull(req.notes()));
        return toSupplierTemplate(globalSupplierTemplateRepository.save(template));
    }

    @Transactional
    public SupplierTemplateResponse patchSupplierTemplate(
            String id,
            String catalogId,
            PatchSupplierTemplateRequest req
    ) {
        GlobalSupplierTemplate template = resolveSupplierTemplate(id, catalogId);
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            template.setName(req.name().trim());
        }
        if (req.supplierType() != null && !req.supplierType().isBlank()) {
            template.setSupplierType(req.supplierType().trim().toLowerCase(Locale.ROOT));
        }
        if (req.vatPin() != null) {
            template.setVatPin(blankToNull(req.vatPin()));
        }
        if (req.notes() != null) {
            template.setNotes(blankToNull(req.notes()));
        }
        return toSupplierTemplate(globalSupplierTemplateRepository.save(template));
    }

    @Transactional(readOnly = true)
    public List<ProductSupplierLinkResponse> listProductSupplierLinks(String productId, String catalogId) {
        GlobalProduct product = requireProduct(productId, catalogId);
        return globalProductSupplierLinkRepository.findByGlobalProductId(product.getId()).stream()
                .map(this::toProductSupplierLink)
                .toList();
    }

    @Transactional
    public ProductSupplierLinkResponse upsertProductSupplierLink(
            String productId,
            String catalogId,
            UpsertProductSupplierLinkRequest req
    ) {
        GlobalProduct product = requireProduct(productId, catalogId);
        GlobalCatalog catalog = requireCatalog(
                catalogId != null && !catalogId.isBlank() ? catalogId : product.getCatalogId());
        GlobalSupplierTemplate template = globalSupplierTemplateRepository
                .findByIdAndCatalogId(req.globalSupplierTemplateId(), catalog.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier template not found"));

        GlobalProductSupplierLink link = globalProductSupplierLinkRepository
                .findByGlobalProductIdAndGlobalSupplierTemplateId(product.getId(), template.getId())
                .orElseGet(GlobalProductSupplierLink::new);
        link.setGlobalProductId(product.getId());
        link.setGlobalSupplierTemplateId(template.getId());
        if (req.defaultCostPrice() != null) {
            link.setDefaultCostPrice(req.defaultCostPrice());
        }
        if (req.supplierSku() != null) {
            link.setSupplierSku(blankToNull(req.supplierSku()));
        }

        boolean makePrimary = Boolean.TRUE.equals(req.primary())
                || (!link.isPrimary()
                && globalProductSupplierLinkRepository.findPrimaryByGlobalProductId(product.getId()).isEmpty());
        if (makePrimary) {
            clearPrimaryFlags(product.getId());
            link.setPrimary(true);
        } else if (req.primary() != null) {
            link.setPrimary(false);
        }
        return toProductSupplierLink(globalProductSupplierLinkRepository.save(link));
    }

    @Transactional
    public void deleteProductSupplierLink(String productId, String templateId, String catalogId) {
        requireProduct(productId, catalogId);
        GlobalProductSupplierLink link = globalProductSupplierLinkRepository
                .findByGlobalProductIdAndGlobalSupplierTemplateId(productId, templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier link not found"));
        boolean wasPrimary = link.isPrimary();
        globalProductSupplierLinkRepository.delete(link);
        if (wasPrimary) {
            List<GlobalProductSupplierLink> remaining =
                    globalProductSupplierLinkRepository.findByGlobalProductId(productId);
            if (!remaining.isEmpty()) {
                GlobalProductSupplierLink next = remaining.get(0);
                next.setPrimary(true);
                globalProductSupplierLinkRepository.save(next);
            }
        }
    }

    private void clearPrimaryFlags(String productId) {
        for (GlobalProductSupplierLink existing : globalProductSupplierLinkRepository.findByGlobalProductId(productId)) {
            if (existing.isPrimary()) {
                existing.setPrimary(false);
                globalProductSupplierLinkRepository.save(existing);
            }
        }
    }

    private SupplierTemplateResponse toSupplierTemplate(GlobalSupplierTemplate template) {
        return new SupplierTemplateResponse(
                template.getId(),
                template.getCatalogId(),
                template.getCode(),
                template.getName(),
                template.getSupplierType(),
                template.getVatPin(),
                template.getNotes(),
                GlobalCatalogSupplierAdoptLinker.tenantSupplierCode(template.getCode())
        );
    }

    private ProductSupplierLinkResponse toProductSupplierLink(GlobalProductSupplierLink link) {
        GlobalSupplierTemplate template = globalSupplierTemplateRepository
                .findById(link.getGlobalSupplierTemplateId())
                .orElse(null);
        return new ProductSupplierLinkResponse(
                link.getGlobalProductId(),
                link.getGlobalSupplierTemplateId(),
                template != null ? template.getCode() : null,
                template != null ? template.getName() : null,
                link.isPrimary(),
                link.getDefaultCostPrice(),
                link.getSupplierSku()
        );
    }

    private void applyCreateFields(GlobalProduct product, CreateProductRequest req) {
        product.setName(req.name().trim());
        product.setStatus(req.status() == null || req.status().isBlank()
                ? GlobalProductStatus.DRAFT
                : GlobalProductStatus.normalize(req.status()));
        product.setUnitType(blankToNull(req.unitType()) != null ? req.unitType().trim() : "each");
        product.setSellable(req.sellable() == null || req.sellable());
        product.setStocked(req.stocked() == null || req.stocked());
        product.setWeighed(Boolean.TRUE.equals(req.weighed()));
        product.setBarcode(blankToNull(req.barcode()));
        product.setSkuTemplate(blankToNull(req.skuTemplate()));
        product.setBrand(blankToNull(req.brand()));
        product.setSize(blankToNull(req.size()));
        product.setVariantName(blankToNull(req.variantName()));
        product.setDescription(blankToNull(req.description()));
        product.setPackageVariant(Boolean.TRUE.equals(req.packageVariant()));
        product.setVariantOfGlobalProductId(
                resolveVariantParentId(product.getCatalogId(), product.getId(), req.variantOfGlobalProductId()));
        product.setPackagingUnitName(blankToNull(req.packagingUnitName()));
        product.setPackagingUnitQty(req.packagingUnitQty());
        product.setGlobalCategoryId(blankToNull(req.globalCategoryId()));
        product.setItemTypeKeyHint(blankToNull(req.itemTypeKeyHint()) != null ? req.itemTypeKeyHint().trim() : "goods");
        product.setSortOrder(req.sortOrder() != null ? req.sortOrder() : 0);
        product.setRecommendedBuyingPrice(req.recommendedBuyingPrice());
        product.setRecommendedSellingPrice(req.recommendedSellingPrice());
        product.setSuggestedMarginPct(req.suggestedMarginPct());
        product.setDefaultMinStockLevel(req.defaultMinStockLevel());
        product.setDefaultReorderLevel(req.defaultReorderLevel());
        product.setDefaultReorderQty(req.defaultReorderQty());
        product.setHasExpiry(Boolean.TRUE.equals(req.hasExpiry()));
        product.setExpiresAfterDays(req.expiresAfterDays());
    }

    private void applyPatchFields(GlobalProduct product, PatchProductRequest req) {
        if (req.name() != null) {
            if (req.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
            }
            product.setName(req.name().trim());
        }
        if (req.status() != null) {
            product.setStatus(GlobalProductStatus.normalize(req.status()));
        }
        if (req.unitType() != null) {
            product.setUnitType(req.unitType().trim());
        }
        if (req.sellable() != null) {
            product.setSellable(req.sellable());
        }
        if (req.stocked() != null) {
            product.setStocked(req.stocked());
        }
        if (req.weighed() != null) {
            product.setWeighed(req.weighed());
        }
        if (req.barcode() != null) {
            product.setBarcode(blankToNull(req.barcode()));
        }
        if (req.skuTemplate() != null) {
            product.setSkuTemplate(blankToNull(req.skuTemplate()));
        }
        if (req.brand() != null) {
            product.setBrand(blankToNull(req.brand()));
        }
        if (req.size() != null) {
            product.setSize(blankToNull(req.size()));
        }
        if (req.variantName() != null) {
            product.setVariantName(blankToNull(req.variantName()));
        }
        if (req.description() != null) {
            product.setDescription(blankToNull(req.description()));
        }
        if (req.packageVariant() != null) {
            product.setPackageVariant(req.packageVariant());
        }
        if (req.variantOfGlobalProductId() != null) {
            product.setVariantOfGlobalProductId(
                    resolveVariantParentId(
                            product.getCatalogId(),
                            product.getId(),
                            blankToNull(req.variantOfGlobalProductId())));
        }
        if (req.packagingUnitName() != null) {
            product.setPackagingUnitName(blankToNull(req.packagingUnitName()));
        }
        if (req.packagingUnitQty() != null) {
            product.setPackagingUnitQty(req.packagingUnitQty());
        }
        if (req.globalCategoryId() != null) {
            product.setGlobalCategoryId(blankToNull(req.globalCategoryId()));
        }
        if (req.itemTypeKeyHint() != null) {
            product.setItemTypeKeyHint(blankToNull(req.itemTypeKeyHint()) != null
                    ? req.itemTypeKeyHint().trim()
                    : "goods");
        }
        if (req.sortOrder() != null) {
            product.setSortOrder(req.sortOrder());
        }
        if (req.recommendedBuyingPrice() != null) {
            product.setRecommendedBuyingPrice(req.recommendedBuyingPrice());
        }
        if (req.recommendedSellingPrice() != null) {
            product.setRecommendedSellingPrice(req.recommendedSellingPrice());
        }
        if (req.suggestedMarginPct() != null) {
            product.setSuggestedMarginPct(req.suggestedMarginPct());
        }
        if (req.defaultMinStockLevel() != null) {
            product.setDefaultMinStockLevel(req.defaultMinStockLevel());
        }
        if (req.defaultReorderLevel() != null) {
            product.setDefaultReorderLevel(req.defaultReorderLevel());
        }
        if (req.defaultReorderQty() != null) {
            product.setDefaultReorderQty(req.defaultReorderQty());
        }
        if (req.hasExpiry() != null) {
            product.setHasExpiry(req.hasExpiry());
        }
        if (req.expiresAfterDays() != null) {
            product.setExpiresAfterDays(req.expiresAfterDays());
        }
    }

    private void applyCategory(GlobalCategory category, UpsertCategoryRequest req, boolean creating) {
        category.setName(req.name().trim());
        String slug = blankToNull(req.slug());
        if (slug == null) {
            slug = slugify(req.name());
        }
        category.setSlug(slug);
        category.setParentId(blankToNull(req.parentId()));
        category.setTenantCategorySlugHint(blankToNull(req.tenantCategorySlugHint()));
        if (req.position() != null) {
            category.setPosition(req.position());
        } else if (creating) {
            category.setPosition(0);
        }
        if (req.active() != null) {
            category.setActive(req.active());
        } else if (creating) {
            category.setActive(true);
        }
    }

    private void assertBarcodeAvailable(String catalogId, String barcode, String status, String excludeId) {
        String normalized = blankToNull(barcode);
        if (normalized == null || GlobalProductStatus.ARCHIVED.equals(status)) {
            return;
        }
        long conflicts = excludeId == null
                ? globalProductRepository.countByCatalogIdAndBarcodeAndStatusNot(
                        catalogId, normalized, GlobalProductStatus.ARCHIVED)
                : globalProductRepository.countByCatalogIdAndBarcodeAndStatusNotAndIdNot(
                        catalogId, normalized, GlobalProductStatus.ARCHIVED, excludeId);
        if (conflicts > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Another non-archived global product already uses barcode " + normalized);
        }
    }

    private boolean hasBarcodeDuplicateWarning(GlobalProduct product) {
        String barcode = blankToNull(product.getBarcode());
        if (barcode == null || GlobalProductStatus.ARCHIVED.equals(product.getStatus())) {
            return false;
        }
        return globalProductRepository.countByCatalogIdAndBarcodeAndStatusNotAndIdNot(
                product.getCatalogId(), barcode, GlobalProductStatus.ARCHIVED, product.getId()) > 0;
    }

    private GlobalProductPack resolvePack(String id, String catalogId) {
        if (catalogId != null && !catalogId.isBlank()) {
            GlobalCatalog catalog = requireCatalog(catalogId);
            return globalProductPackRepository.findByIdAndCatalogId(id, catalog.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
        }
        return globalProductPackRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));
    }

    private GlobalSupplierTemplate resolveSupplierTemplate(String id, String catalogId) {
        if (catalogId != null && !catalogId.isBlank()) {
            GlobalCatalog catalog = requireCatalog(catalogId);
            return globalSupplierTemplateRepository.findByIdAndCatalogId(id, catalog.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier template not found"));
        }
        return globalSupplierTemplateRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier template not found"));
    }

    private GlobalCatalog requireCatalog(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return globalCatalogRepository.findByCode(DEFAULT_CATALOG_CODE)
                    .or(() -> globalCatalogRepository.findFirstByStatusOrderByVersionDesc(GlobalProductStatus.PUBLISHED))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No global catalog available"));
        }
        String key = catalogId.trim();
        return globalCatalogRepository.findById(key)
                .or(() -> globalCatalogRepository.findByCode(key))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global catalog not found"));
    }

    private GlobalProduct requireProduct(String id, String catalogId) {
        GlobalProduct product = globalProductRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global product not found"));
        if (catalogId != null && !catalogId.isBlank()) {
            GlobalCatalog catalog = requireCatalog(catalogId);
            if (!catalog.getId().equals(product.getCatalogId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Global product not found");
            }
        }
        return product;
    }

    private Map<String, List<GlobalProductImage>> imagesByProductId(List<GlobalProduct> products) {
        if (products == null || products.isEmpty()) {
            return Map.of();
        }
        List<String> ids = products.stream().map(GlobalProduct::getId).toList();
        Map<String, List<GlobalProductImage>> out = new HashMap<>();
        for (GlobalProductImage image : galleryService.listForProducts(ids)) {
            out.computeIfAbsent(image.getGlobalProductId(), ignored -> new ArrayList<>()).add(image);
        }
        return out;
    }

    private ProductResponse toProduct(GlobalProduct gp, List<GlobalProductImage> images) {
        List<ProductImageResponse> imageResponses = images == null
                ? List.of()
                : images.stream()
                        .map(img -> new ProductImageResponse(
                                img.getId(),
                                img.getImageUrl(),
                                img.getImagePublicId(),
                                img.getSortOrder(),
                                img.getAltText(),
                                img.getWidth(),
                                img.getHeight()))
                        .toList();
        String coverUrl = blankToNull(gp.getImageUrl());
        if (coverUrl == null && !imageResponses.isEmpty()) {
            coverUrl = imageResponses.get(0).imageUrl();
        }
        return new ProductResponse(
                gp.getId(),
                gp.getCatalogId(),
                gp.getGlobalCategoryId(),
                gp.getSkuTemplate(),
                gp.getName(),
                gp.getBrand(),
                gp.getSize(),
                gp.getVariantName(),
                gp.getDescription(),
                gp.getBarcode(),
                gp.getUnitType(),
                gp.isWeighed(),
                gp.isSellable(),
                gp.isStocked(),
                gp.isPackageVariant(),
                gp.getVariantOfGlobalProductId(),
                gp.getPackagingUnitName(),
                gp.getPackagingUnitQty(),
                gp.getRecommendedBuyingPrice(),
                gp.getRecommendedSellingPrice(),
                gp.getSuggestedMarginPct(),
                gp.getDefaultReorderLevel(),
                gp.getDefaultReorderQty(),
                gp.getDefaultMinStockLevel(),
                gp.isHasExpiry(),
                gp.getExpiresAfterDays(),
                coverUrl,
                gp.getImagePublicId(),
                imageResponses,
                gp.getItemTypeKeyHint(),
                gp.getStatus(),
                gp.getSortOrder(),
                gp.getVersion(),
                hasBarcodeDuplicateWarning(gp)
        );
    }

    /**
     * Parent must exist in the same catalog, must not be self, and must not itself be a variant.
     * Blank clears the link.
     */
    private String resolveVariantParentId(String catalogId, String productId, String rawParentId) {
        String parentId = blankToNull(rawParentId);
        if (parentId == null) {
            return null;
        }
        if (productId != null && productId.equals(parentId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Product cannot be its own parent");
        }
        GlobalProduct parent = globalProductRepository.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent global product not found"));
        if (!catalogId.equals(parent.getCatalogId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent must be in the same catalog");
        }
        if (blankToNull(parent.getVariantOfGlobalProductId()) != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot nest a variant under another variant");
        }
        return parentId;
    }

    private CategoryResponse toCategory(GlobalCategory c) {
        return new CategoryResponse(
                c.getId(),
                c.getParentId(),
                c.getName(),
                c.getSlug(),
                c.getTenantCategorySlugHint(),
                c.getPosition(),
                c.isActive()
        );
    }

    private static String slugify(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "category" : slug.substring(0, Math.min(slug.length(), 191));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
