package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.catalog.api.dto.AisleResponse;
import zelisline.ub.catalog.api.dto.CategoryResponse;
import zelisline.ub.catalog.api.dto.CategorySupplierSummaryResponse;
import zelisline.ub.catalog.api.dto.CreateAisleRequest;
import zelisline.ub.catalog.api.dto.CreateCategoryRequest;
import zelisline.ub.catalog.api.dto.CategoryLinkedPriceRuleResponse;
import zelisline.ub.catalog.api.dto.CategoryTreeNodeResponse;
import zelisline.ub.catalog.api.dto.CreateCategorySupplierLinkRequest;
import zelisline.ub.catalog.api.dto.CreateItemTypeRequest;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.ItemTypeResponse;
import zelisline.ub.catalog.api.dto.PatchCategoryRequest;
import zelisline.ub.catalog.api.dto.PostCategoryPriceRuleRequest;
import zelisline.ub.catalog.api.dto.TaxRateSummaryResponse;
import zelisline.ub.catalog.domain.Aisle;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.CategoryPriceRule;
import zelisline.ub.catalog.domain.CategoryPriceRuleId;
import zelisline.ub.catalog.domain.CategoryImage;
import zelisline.ub.catalog.domain.CategorySupplierLink;
import zelisline.ub.catalog.domain.ItemImageStorageProvider;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.CategoryPriceRuleRepository;
import zelisline.ub.catalog.repository.CategoryImageRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.CategorySupplierLinkRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.pricing.domain.PriceRule;
import zelisline.ub.pricing.domain.TaxRate;
import zelisline.ub.pricing.repository.PriceRuleRepository;
import zelisline.ub.pricing.repository.TaxRateRepository;

import org.springframework.data.domain.Sort;

@Service
@RequiredArgsConstructor
@Slf4j
public class CatalogTaxonomyService {

    private final CategoryRepository categoryRepository;
    private final CategoryImageRepository categoryImageRepository;
    private final CategorySupplierLinkRepository categorySupplierLinkRepository;
    private final SupplierRepository supplierRepository;
    private final CloudinaryImageService cloudinaryImageService;
    private final AisleRepository aisleRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final TaxRateRepository taxRateRepository;
    private final CategoryPriceRuleRepository categoryPriceRuleRepository;
    private final PriceRuleRepository priceRuleRepository;

    private static final Comparator<CategorySupplierLink> CATEGORY_SUPPLIER_ORDER =
            Comparator.comparing(CategorySupplierLink::isPrimaryLink).reversed()
                    .thenComparingInt(CategorySupplierLink::getSortOrder)
                    .thenComparing(CategorySupplierLink::getSupplierId);

    private static final Comparator<Category> CATEGORY_SIBLING_ORDER =
            Comparator.comparingInt(Category::getPosition)
                    .thenComparing(Category::getName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Category::getId);

    @Transactional(readOnly = true)
    public List<CategoryResponse> listCategories(String businessId) {
        List<Category> cats = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
        return mapCategoriesToResponses(businessId, cats);
    }

    private List<CategoryResponse> mapCategoriesToResponses(String businessId, List<Category> cats) {
        if (cats.isEmpty()) {
            return List.of();
        }
        List<String> ids = cats.stream().map(Category::getId).toList();
        Map<String, String> galleryFirst = firstGallerySecureUrlByCategoryIds(ids);

        List<CategorySupplierLink> allLinks =
                categorySupplierLinkRepository.findByBusinessIdOrderByCategoryIdAscSortOrderAscSupplierIdAsc(businessId);
        Map<String, List<CategorySupplierLink>> linksByCategory = groupLinksByCategory(allLinks);

        HashSet<String> supplierIds = new HashSet<>();
        for (CategorySupplierLink l : allLinks) {
            supplierIds.add(l.getSupplierId());
        }
        Map<String, String> supplierNames = loadSupplierNames(businessId, supplierIds);

        Map<String, TaxRate> taxMap = taxRatesForCategories(businessId, cats);

        return cats.stream()
                .map(c -> toCategoryResponse(businessId, c, galleryFirst, linksByCategory, supplierNames, taxMap))
                .toList();
    }

    private Map<String, TaxRate> taxRatesForCategories(String businessId, List<Category> cats) {
        Set<String> taxIds = new HashSet<>();
        for (Category c : cats) {
            String tid = c.getDefaultTaxRateId();
            if (tid != null && !tid.isBlank()) {
                taxIds.add(tid.trim());
            }
        }
        if (taxIds.isEmpty()) {
            return Map.of();
        }
        Map<String, TaxRate> out = new LinkedHashMap<>();
        for (TaxRate t : taxRateRepository.findByBusinessIdAndIdIn(businessId, taxIds)) {
            out.put(t.getId(), t);
        }
        return out;
    }

    private static TaxRateSummaryResponse summarizeTax(Map<String, TaxRate> taxById, String taxRateId) {
        if (taxRateId == null || taxRateId.isBlank()) {
            return null;
        }
        TaxRate t = taxById.get(taxRateId.trim());
        if (t == null) {
            return null;
        }
        return new TaxRateSummaryResponse(t.getId(), t.getName(), t.getRatePercent(), t.isInclusive());
    }

    @Transactional(readOnly = true)
    public List<CategoryTreeNodeResponse> categoryTree(String businessId) {
        List<Category> cats = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
        if (cats.isEmpty()) {
            return List.of();
        }
        List<String> ids = cats.stream().map(Category::getId).toList();
        Map<String, String> galleryFirst = firstGallerySecureUrlByCategoryIds(ids);
        Map<String, List<Category>> byParent = new LinkedHashMap<>();
        for (Category c : cats) {
            String pk = c.getParentId() == null ? "" : c.getParentId();
            byParent.computeIfAbsent(pk, k -> new ArrayList<>()).add(c);
        }
        byParent.values().forEach(row -> row.sort(CATEGORY_SIBLING_ORDER));
        return buildCategoryTreeNodes(byParent, "", galleryFirst, 0);
    }

    private List<CategoryTreeNodeResponse> buildCategoryTreeNodes(
            Map<String, List<Category>> byParent,
            String parentKey,
            Map<String, String> galleryFirst,
            int depth
    ) {
        List<Category> row = byParent.getOrDefault(parentKey, List.of());
        List<CategoryTreeNodeResponse> out = new ArrayList<>(row.size());
        for (Category c : row) {
            List<CategoryTreeNodeResponse> children =
                    buildCategoryTreeNodes(byParent, c.getId(), galleryFirst, depth + 1);
            String thumb = resolveCategoryThumbnail(c, galleryFirst);
            String desc = trimToNull(c.getDescription());
            out.add(new CategoryTreeNodeResponse(
                    c.getId(),
                    c.getName(),
                    c.getSlug(),
                    c.getParentId(),
                    c.getPosition(),
                    depth,
                    c.isActive(),
                    thumb,
                    desc != null ? desc : "",
                    children.size(),
                    children));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<CategoryResponse> categoryChildren(String businessId, String parentId) {
        assertCategoryInBusiness(businessId, parentId);
        return listCategories(businessId).stream()
                .filter(r -> Objects.equals(parentId, r.parentId()))
                .sorted(Comparator.comparingInt(CategoryResponse::position)
                        .thenComparing(CategoryResponse::name, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(CategoryResponse::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<CategoryLinkedPriceRuleResponse> listCategoryPriceRules(String businessId, String categoryId) {
        assertCategoryInBusiness(businessId, categoryId);
        List<CategoryLinkedPriceRuleResponse> out = new ArrayList<>();
        for (CategoryPriceRule row : categoryPriceRuleRepository.findByCategoryOrdered(categoryId)) {
            String rid = row.getId().getPriceRuleId();
            PriceRule pr = priceRuleRepository.findByIdAndBusinessId(rid, businessId).orElse(null);
            String name = pr != null ? pr.getName() : rid;
            out.add(new CategoryLinkedPriceRuleResponse(rid, name, row.getPrecedence()));
        }
        return out;
    }

    @Transactional
    public CategoryLinkedPriceRuleResponse linkCategoryPriceRule(
            String businessId,
            String categoryId,
            PostCategoryPriceRuleRequest req
    ) {
        assertCategoryInBusiness(businessId, categoryId);
        String rid = req.ruleId().trim();
        priceRuleRepository.findByIdAndBusinessId(rid, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Price rule not found"));
        CategoryPriceRuleId pk = new CategoryPriceRuleId(categoryId, rid);
        if (categoryPriceRuleRepository.existsById(pk)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Price rule already linked to this category");
        }
        int prec = req.precedence() != null ? req.precedence() : nextCategoryPriceRulePrecedence(categoryId);
        CategoryPriceRule row = new CategoryPriceRule();
        row.setId(pk);
        row.setPrecedence(prec);
        categoryPriceRuleRepository.save(row);
        PriceRule pr = priceRuleRepository.findByIdAndBusinessId(rid, businessId).orElseThrow();
        return new CategoryLinkedPriceRuleResponse(rid, pr.getName(), prec);
    }

    @Transactional
    public void unlinkCategoryPriceRule(String businessId, String categoryId, String ruleId) {
        assertCategoryInBusiness(businessId, categoryId);
        CategoryPriceRuleId pk = new CategoryPriceRuleId(categoryId, ruleId.trim());
        if (!categoryPriceRuleRepository.existsById(pk)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Category price rule link not found");
        }
        categoryPriceRuleRepository.deleteById(pk);
    }

    private int nextCategoryPriceRulePrecedence(String categoryId) {
        return categoryPriceRuleRepository.findByCategoryOrdered(categoryId).stream()
                .mapToInt(CategoryPriceRule::getPrecedence)
                .max()
                .orElse(-1) + 1;
    }

    private void assertTaxRateAllowed(String businessId, String taxRateId) {
        if (taxRateId == null || taxRateId.isBlank()) {
            return;
        }
        taxRateRepository.findByIdAndBusinessId(taxRateId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tax rate not found"));
    }

    @Transactional(readOnly = true)
    public List<ItemImageResponse> listCategoryImages(String businessId, String categoryId) {
        assertCategoryInBusiness(businessId, categoryId);
        return categoryImageRepository.findByCategoryIdOrderBySortOrderAscIdAsc(categoryId).stream()
                .map(CatalogTaxonomyService::toCategoryImageApiResponse)
                .toList();
    }

    @Transactional
    public ItemImageResponse uploadCategoryImageCloudinary(
            String businessId,
            String categoryId,
            byte[] bytes,
            String originalFilename,
            String altText,
            boolean primary
    ) {
        Category category = assertCategoryInBusiness(businessId, categoryId);
        CloudinaryUploadResult r = cloudinaryImageService.uploadImageToFolder(
                bytes,
                originalFilename,
                CloudinaryImageService.folderCategories(businessId, categoryId));
        int sortOrder = categoryImageRepository.maxSortOrderForCategory(categoryId) + 1;
        CategoryImage img = new CategoryImage();
        img.setCategoryId(categoryId);
        img.setProvider(ItemImageStorageProvider.CLOUDINARY);
        img.setCloudinaryPublicId(r.publicId());
        img.setSecureUrl(r.secureUrl());
        img.setS3Key(r.publicId());
        img.setWidth(r.width());
        img.setHeight(r.height());
        img.setBytes(r.bytes());
        img.setFormat(trimToNull(r.format()));
        img.setContentType(trimToNull(r.contentType()));
        img.setAssetSignature(trimToNull(r.versionSignature()));
        img.setPredominantColorHex(normalizeHex(trimToNull(r.predominantColorHex())));
        img.setPhash(trimToNull(r.phash()));
        img.setAltText(trimToNull(altText));
        img.setSortOrder(sortOrder);
        categoryImageRepository.save(img);
        if (primary) {
            category.setImageKey(r.secureUrl());
            categoryRepository.save(category);
        }
        return toCategoryImageApiResponse(img);
    }

    @Transactional
    public void deleteCategoryImage(String businessId, String categoryId, String imageId) {
        Category category = assertCategoryInBusiness(businessId, categoryId);
        CategoryImage img = categoryImageRepository.findByIdAndCategoryId(imageId, categoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        String matchUrl = img.getSecureUrl();
        String matchKey = img.getS3Key();
        if (ItemImageStorageProvider.CLOUDINARY.equals(img.getProvider())) {
            String pid = img.getCloudinaryPublicId();
            if (pid != null && !pid.isBlank() && cloudinaryImageService.isConfigured()) {
                try {
                    cloudinaryImageService.destroyImage(pid);
                } catch (Exception ex) {
                    log.warn("Cloudinary destroy failed for category image public_id={}: {}", pid, ex.toString());
                }
            }
        }
        categoryImageRepository.delete(img);
        boolean matchesPrimary = category.getImageKey() != null
                && ((matchUrl != null && matchUrl.equals(category.getImageKey()))
                        || (matchKey != null && matchKey.equals(category.getImageKey())));
        if (matchesPrimary) {
            category.setImageKey(null);
            categoryRepository.save(category);
        }
    }

    @Transactional
    public CategorySupplierSummaryResponse addCategorySupplierLink(
            String businessId,
            String categoryId,
            CreateCategorySupplierLinkRequest request
    ) {
        assertCategoryInBusiness(businessId, categoryId);
        String supplierId = request.supplierId().trim();
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
        if (categorySupplierLinkRepository.existsByBusinessIdAndCategoryIdAndSupplierId(businessId, categoryId, supplierId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Supplier already linked to this category");
        }
        boolean primary = Boolean.TRUE.equals(request.primary());
        if (primary) {
            List<CategorySupplierLink> peers =
                    categorySupplierLinkRepository.findByBusinessIdAndCategoryIdOrderBySortOrderAscSupplierIdAsc(
                            businessId, categoryId);
            for (CategorySupplierLink peer : peers) {
                if (peer.isPrimaryLink()) {
                    peer.setPrimaryLink(false);
                    categorySupplierLinkRepository.save(peer);
                }
            }
        }
        int sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : nextSupplierLinkSortOrder(businessId, categoryId);
        CategorySupplierLink row = new CategorySupplierLink();
        row.setBusinessId(businessId);
        row.setCategoryId(categoryId);
        row.setSupplierId(supplierId);
        row.setSortOrder(sortOrder);
        row.setPrimaryLink(primary);
        categorySupplierLinkRepository.save(row);
        return new CategorySupplierSummaryResponse(supplierId, supplier.getName(), sortOrder, primary);
    }

    @Transactional
    public void removeCategorySupplierLink(String businessId, String categoryId, String supplierId) {
        assertCategoryInBusiness(businessId, categoryId);
        CategorySupplierLink row = categorySupplierLinkRepository
                .findByBusinessIdAndCategoryIdAndSupplierId(businessId, categoryId, supplierId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        categorySupplierLinkRepository.delete(row);
    }

    @Transactional
    public CategorySupplierSummaryResponse patchCategorySupplierLink(
            String businessId,
            String categoryId,
            String supplierId,
            boolean primary
    ) {
        assertCategoryInBusiness(businessId, categoryId);
        CategorySupplierLink row = categorySupplierLinkRepository
                .findByBusinessIdAndCategoryIdAndSupplierId(businessId, categoryId, supplierId.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
        if (primary) {
            List<CategorySupplierLink> peers =
                    categorySupplierLinkRepository.findByBusinessIdAndCategoryIdOrderBySortOrderAscSupplierIdAsc(
                            businessId, categoryId);
            for (CategorySupplierLink peer : peers) {
                if (peer.isPrimaryLink() && !peer.getSupplierId().equals(row.getSupplierId())) {
                    peer.setPrimaryLink(false);
                    categorySupplierLinkRepository.save(peer);
                }
            }
        }
        row.setPrimaryLink(primary);
        categorySupplierLinkRepository.save(row);
        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(row.getSupplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
        return new CategorySupplierSummaryResponse(
                row.getSupplierId(),
                supplier.getName(),
                row.getSortOrder(),
                row.isPrimaryLink());
    }

    private int nextSupplierLinkSortOrder(String businessId, String categoryId) {
        List<CategorySupplierLink> existing =
                categorySupplierLinkRepository.findByBusinessIdAndCategoryIdOrderBySortOrderAscSupplierIdAsc(
                        businessId, categoryId);
        return existing.stream().mapToInt(CategorySupplierLink::getSortOrder).max().orElse(-1) + 1;
    }

    private Category assertCategoryInBusiness(String businessId, String categoryId) {
        return categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    }

    private static Map<String, List<CategorySupplierLink>> groupLinksByCategory(List<CategorySupplierLink> allLinks) {
        Map<String, List<CategorySupplierLink>> map = new LinkedHashMap<>();
        for (CategorySupplierLink l : allLinks) {
            map.computeIfAbsent(l.getCategoryId(), k -> new ArrayList<>()).add(l);
        }
        return map;
    }

    private Map<String, String> loadSupplierNames(String businessId, Collection<String> supplierIds) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String sid : supplierIds) {
            supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sid, businessId).ifPresent(s -> out.put(sid, s.getName()));
        }
        return out;
    }

    private Map<String, String> firstGallerySecureUrlByCategoryIds(Collection<String> categoryIds) {
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        Sort sort = Sort.by(Sort.Order.asc("categoryId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<CategoryImage> imgs = categoryImageRepository.findByCategoryIdIn(categoryIds, sort);
        Map<String, String> out = new LinkedHashMap<>();
        for (CategoryImage img : imgs) {
            String url = img.getSecureUrl();
            if (url != null && !url.isBlank()) {
                out.putIfAbsent(img.getCategoryId(), url.trim());
            }
        }
        return out;
    }

    private CategoryResponse toCategoryResponse(
            String businessId,
            Category c,
            Map<String, String> galleryFirstUrlByCategoryId,
            Map<String, List<CategorySupplierLink>> linksByCategory,
            Map<String, String> supplierNames,
            Map<String, TaxRate> taxById
    ) {
        List<CategorySupplierLink> rawLinks = linksByCategory.getOrDefault(c.getId(), List.of());
        List<CategorySupplierLink> sortedLinks = new ArrayList<>(rawLinks);
        sortedLinks.sort(CATEGORY_SUPPLIER_ORDER);
        List<CategorySupplierSummaryResponse> suppliers = sortedLinks.stream()
                .map(l -> new CategorySupplierSummaryResponse(
                        l.getSupplierId(),
                        supplierNames.getOrDefault(l.getSupplierId(), "—"),
                        l.getSortOrder(),
                        l.isPrimaryLink()))
                .toList();
        String description = trimToNull(c.getDescription());
        return new CategoryResponse(
                c.getId(),
                c.getName(),
                c.getSlug(),
                c.getPosition(),
                c.getIcon(),
                c.getParentId(),
                c.isActive(),
                description,
                c.getDefaultMarkupPct(),
                c.getDefaultTaxRateId(),
                summarizeTax(taxById, c.getDefaultTaxRateId()),
                c.getImageKey(),
                resolveCategoryThumbnail(c, galleryFirstUrlByCategoryId),
                suppliers);
    }

    private CategoryResponse buildCategoryResponse(String businessId, Category c) {
        Map<String, String> gallery = firstGallerySecureUrlByCategoryIds(List.of(c.getId()));
        List<CategorySupplierLink> links =
                categorySupplierLinkRepository.findByBusinessIdAndCategoryIdOrderBySortOrderAscSupplierIdAsc(
                        businessId, c.getId());
        Map<String, List<CategorySupplierLink>> grouped = groupLinksByCategory(links);
        HashSet<String> sids = links.stream().map(CategorySupplierLink::getSupplierId).collect(Collectors.toCollection(HashSet::new));
        Map<String, String> names = loadSupplierNames(businessId, sids);
        Map<String, TaxRate> taxMap = taxRatesForCategories(businessId, List.of(c));
        return toCategoryResponse(businessId, c, gallery, grouped, names, taxMap);
    }

    private static String resolveCategoryThumbnail(Category c, Map<String, String> galleryFirstUrlByCategoryId) {
        String k = c.getImageKey();
        if (k != null && (k.startsWith("http://") || k.startsWith("https://"))) {
            return k.trim();
        }
        return galleryFirstUrlByCategoryId.get(c.getId());
    }

    private static ItemImageResponse toCategoryImageApiResponse(CategoryImage img) {
        return new ItemImageResponse(
                img.getId(),
                img.getS3Key(),
                img.getSecureUrl(),
                img.getCloudinaryPublicId(),
                img.getProvider(),
                img.getWidth(),
                img.getHeight(),
                img.getSortOrder(),
                img.getContentType(),
                img.getAltText(),
                img.getBytes(),
                img.getFormat(),
                img.getAssetSignature(),
                img.getPredominantColorHex(),
                img.getPhash(),
                img.getCreatedAt());
    }

    @Transactional
    public CategoryResponse createCategory(String businessId, CreateCategoryRequest request) {
        if (request.parentId() != null && !request.parentId().isBlank()) {
            String pid = request.parentId().trim();
            categoryRepository.findByIdAndBusinessId(pid, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent category not found"));
            if (CategoryCycleDetector.wouldIntroduceCycle(null, pid, id ->
                    categoryRepository.findByIdAndBusinessId(id, businessId).map(Category::getParentId))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parent category");
            }
        }

        Category entity = new Category();
        entity.setBusinessId(businessId);
        entity.setName(request.name().trim());
        entity.setSlug(uniqueSlug(businessId, TaxonomySlug.fromName(request.name())));
        entity.setPosition(request.position() != null ? request.position() : 0);
        entity.setIcon(trimToNull(request.icon()));
        entity.setParentId(trimToNull(request.parentId()));
        entity.setActive(true);
        entity.setDescription(trimToNull(request.description()));
        entity.setDefaultMarkupPct(request.defaultMarkupPct());
        entity.setDefaultTaxRateId(trimToNull(request.defaultTaxRateId()));
        assertTaxRateAllowed(businessId, entity.getDefaultTaxRateId());
        categoryRepository.save(entity);
        return buildCategoryResponse(businessId, entity);
    }

    @Transactional
    public CategoryResponse patchCategory(String businessId, String categoryId, PatchCategoryRequest request) {
        Category category = categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));

        if (request.name() != null && !request.name().isBlank()) {
            category.setName(request.name().trim());
        }
        if (request.slug() != null && !request.slug().isBlank()) {
            String next = request.slug().trim().toLowerCase(Locale.ROOT);
            if (!next.equals(category.getSlug()) && categoryRepository.existsByBusinessIdAndSlug(businessId, next)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Slug already in use");
            }
            category.setSlug(next);
        }
        if (request.icon() != null) {
            category.setIcon(trimToNull(request.icon()));
        }
        if (request.active() != null) {
            category.setActive(request.active());
        }
        if (request.position() != null) {
            category.setPosition(request.position());
        }
        if (Boolean.TRUE.equals(request.root())) {
            category.setParentId(null);
        } else if (request.parentId() != null && !request.parentId().isBlank()) {
            String newParent = request.parentId().trim();
            if (!categoryRepository.findByIdAndBusinessId(newParent, businessId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Parent category not found");
            }
            if (CategoryCycleDetector.wouldIntroduceCycle(category.getId(), newParent, id ->
                    categoryRepository.findByIdAndBusinessId(id, businessId).map(Category::getParentId))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category parent would create a cycle");
            }
            category.setParentId(newParent);
        }

        if (Boolean.TRUE.equals(request.clearDescription())) {
            category.setDescription(null);
        } else if (request.description() != null) {
            category.setDescription(trimToNull(request.description()));
        }
        if (Boolean.TRUE.equals(request.clearDefaultMarkup())) {
            category.setDefaultMarkupPct(null);
        } else if (request.defaultMarkupPct() != null) {
            category.setDefaultMarkupPct(request.defaultMarkupPct());
        }
        if (Boolean.TRUE.equals(request.clearDefaultTaxRate())) {
            category.setDefaultTaxRateId(null);
        } else if (request.defaultTaxRateId() != null) {
            String tid = trimToNull(request.defaultTaxRateId());
            assertTaxRateAllowed(businessId, tid);
            category.setDefaultTaxRateId(tid);
        }

        categoryRepository.save(category);
        return buildCategoryResponse(businessId, category);
    }

    @Transactional(readOnly = true)
    public List<AisleResponse> listAisles(String businessId) {
        return aisleRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .map(this::toAisleResponse)
                .toList();
    }

    @Transactional
    public AisleResponse createAisle(String businessId, CreateAisleRequest request) {
        String code = request.code().trim();
        if (aisleRepository.existsByBusinessIdAndCode(businessId, code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Aisle code already in use");
        }
        Aisle row = new Aisle();
        row.setBusinessId(businessId);
        row.setName(request.name().trim());
        row.setCode(code);
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        aisleRepository.save(row);
        return toAisleResponse(row);
    }

    @Transactional(readOnly = true)
    public List<ItemTypeResponse> listItemTypes(String businessId) {
        return itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId).stream()
                .map(this::toItemTypeResponse)
                .toList();
    }

    @Transactional
    public ItemTypeResponse createItemType(String businessId, CreateItemTypeRequest request) {
        String key = TaxonomySlug.normalizeItemTypeKey(request.key());
        if (key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid item type key");
        }
        if (itemTypeRepository.existsByBusinessIdAndTypeKey(businessId, key)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Item type key already in use");
        }
        ItemType row = new ItemType();
        row.setBusinessId(businessId);
        row.setTypeKey(key);
        row.setLabel(request.label().trim());
        row.setIcon(trimToNull(request.icon()));
        row.setColor(trimToNull(request.color()));
        row.setSortOrder(request.sortOrder() != null ? request.sortOrder() : 0);
        row.setActive(true);
        itemTypeRepository.save(row);
        return toItemTypeResponse(row);
    }

    private String uniqueSlug(String businessId, String base) {
        String slug = base;
        int i = 2;
        while (categoryRepository.existsByBusinessIdAndSlug(businessId, slug)) {
            slug = base + "-" + i++;
        }
        return slug;
    }

    private AisleResponse toAisleResponse(Aisle a) {
        return new AisleResponse(
                a.getId(),
                a.getName(),
                a.getCode(),
                a.getSortOrder(),
                a.isActive());
    }

    private ItemTypeResponse toItemTypeResponse(ItemType t) {
        return new ItemTypeResponse(
                t.getId(),
                t.getTypeKey(),
                t.getLabel(),
                t.getIcon(),
                t.getColor(),
                t.getSortOrder(),
                t.isActive());
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String normalizeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String t = hex.trim();
        if (t.startsWith("#")) {
            return t.length() > 9 ? t.substring(0, 7) : t;
        }
        return "#" + t.replace("#", "");
    }
}
