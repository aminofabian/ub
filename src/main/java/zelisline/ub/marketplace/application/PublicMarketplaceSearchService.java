package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceProductSearchRow;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.suppliers.SupplierCodes;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Public directory over tenant-owned suppliers and their linked catalogue items.
 * Only active, non-deleted suppliers that have at least one active product link are listed.
 */
@Service
@RequiredArgsConstructor
public class PublicMarketplaceSearchService {

    private final SupplierRepository supplierRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final CategoryRepository categoryRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(String q, Pageable pageable) {
        String query = blankToNull(q);
        Page<Supplier> page = supplierRepository.searchPublicDirectory(query, pageable);
        List<PublicMarketplaceSupplierSearchRow> rows = page.getContent().stream()
                .map(this::toSupplierRow)
                .toList();
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public Page<PublicMarketplaceProductSearchRow> searchProducts(String q, Pageable pageable) {
        String query = blankToNull(q);
        Page<SupplierProduct> page = supplierProductRepository.searchPublicDirectory(query, pageable);
        List<SupplierProduct> links = page.getContent();
        Map<String, Item> itemsById = loadItems(links.stream().map(SupplierProduct::getItemId).toList());
        Map<String, Supplier> suppliersById = loadSuppliers(
                links.stream().map(SupplierProduct::getSupplierId).toList());
        Map<String, String> thumbs = resolveThumbnailUrls(itemsById.values());
        Map<String, String> categories = categoryNamesById(
                itemsById.values().stream().map(Item::getCategoryId).filter(Objects::nonNull).toList());

        List<PublicMarketplaceProductSearchRow> rows = new ArrayList<>(links.size());
        for (SupplierProduct link : links) {
            Item item = itemsById.get(link.getItemId());
            Supplier supplier = suppliersById.get(link.getSupplierId());
            if (item == null || supplier == null) {
                continue;
            }
            String imageUrl = item.getId() != null ? thumbs.get(item.getId()) : null;
            String categoryName = item.getCategoryId() != null
                    ? categories.get(item.getCategoryId())
                    : null;
            rows.add(toProductRow(link, item, supplier, imageUrl, categoryName));
        }
        return new PageImpl<>(rows, pageable, page.getTotalElements());
    }

    @Transactional(readOnly = true)
    public MarketplaceSupplierDetailResponse getSupplierDetail(String supplierId) {
        Supplier supplier = supplierRepository.findByIdAndDeletedAtIsNull(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
        if (!"active".equalsIgnoreCase(supplier.getStatus())
                || SupplierCodes.SYSTEM_UNASSIGNED.equals(supplier.getCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier is not available");
        }

        List<SupplierProduct> links =
                supplierProductRepository.listActivePublicForSupplier(supplier.getId());
        Map<String, Item> itemsById = loadItems(links.stream().map(SupplierProduct::getItemId).toList());
        Map<String, String> thumbs = resolveThumbnailUrls(itemsById.values());
        Map<String, String> categories = categoryNamesById(
                itemsById.values().stream().map(Item::getCategoryId).filter(Objects::nonNull).toList());

        List<MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview> products = new ArrayList<>();
        for (SupplierProduct link : links) {
            Item item = itemsById.get(link.getItemId());
            if (item == null || item.getDeletedAt() != null || !item.isActive()) {
                continue;
            }
            BigDecimal price = link.getDefaultCostPrice() != null
                    ? link.getDefaultCostPrice()
                    : link.getLastCostPrice();
            String displayName = displayItemName(item);
            String categoryName = item.getCategoryId() != null
                    ? categories.get(item.getCategoryId())
                    : null;
            products.add(new MarketplaceSupplierDetailResponse.MarketplaceCatalogProductPreview(
                    link.getId(),
                    displayName,
                    item.getBarcode(),
                    link.getSupplierSku() != null && !link.getSupplierSku().isBlank()
                            ? link.getSupplierSku()
                            : item.getSku(),
                    categoryName,
                    thumbs.get(item.getId()),
                    link.getPackSize(),
                    link.getPackUnit(),
                    link.getMinOrderQty(),
                    price,
                    price != null ? "KES" : null,
                    link.isActive()));
        }

        List<SupplierContact> contactRows = supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId());
        List<MarketplaceSupplierDetailResponse.MarketplaceContactPreview> contacts = contactRows.stream()
                .filter(c -> hasContactInfo(c))
                .map(c -> new MarketplaceSupplierDetailResponse.MarketplaceContactPreview(
                        blankToNull(c.getName()),
                        blankToNull(c.getRoleLabel()),
                        blankToNull(c.getPhone()),
                        blankToNull(c.getEmail()),
                        c.isPrimaryContact()))
                .toList();

        SupplierContact primary = contactRows.stream().findFirst().orElse(null);
        String businessName = businessName(supplier.getBusinessId());
        String description = supplier.getNotes();
        if ((description == null || description.isBlank()) && businessName != null) {
            description = "Supplier listed by " + businessName;
        }

        return new MarketplaceSupplierDetailResponse(
                supplier.getId(),
                supplier.getName(),
                description,
                blankToNull(supplier.getSupplierType()),
                businessName,
                supplier.getStatus(),
                primary != null ? blankToNull(primary.getEmail()) : null,
                primary != null ? blankToNull(primary.getPhone()) : null,
                contacts,
                blankToNull(supplier.getPaymentMethodPreferred()),
                blankToNull(supplier.getPaymentDetails()),
                blankToNull(supplier.getPayoutType()),
                blankToNull(supplier.getPayoutPhone()),
                supplier.getCreditTermsDays(),
                List.of(),
                buildTags(supplier, businessName),
                products);
    }

    private PublicMarketplaceSupplierSearchRow toSupplierRow(Supplier supplier) {
        String businessName = businessName(supplier.getBusinessId());
        String description = supplier.getNotes();
        if ((description == null || description.isBlank()) && businessName != null) {
            description = "Listed by " + businessName;
        }
        List<SupplierContact> contacts = supplierContactRepository
                .findBySupplierIdOrderByPrimaryContactDescNameAsc(supplier.getId());
        SupplierContact primary = contacts.stream().findFirst().orElse(null);
        int productCount = (int) Math.min(
                Integer.MAX_VALUE,
                supplierProductRepository.countActivePublicForSupplier(supplier.getId()));

        return new PublicMarketplaceSupplierSearchRow(
                supplier.getId(),
                supplier.getName(),
                description,
                blankToNull(supplier.getSupplierType()),
                businessName,
                productCount,
                primary != null ? blankToNull(primary.getName()) : null,
                primary != null ? blankToNull(primary.getPhone()) : null,
                primary != null ? blankToNull(primary.getEmail()) : null,
                blankToNull(supplier.getPaymentMethodPreferred()),
                blankToNull(supplier.getPayoutType()),
                List.of(),
                buildTags(supplier, businessName));
    }

    private PublicMarketplaceProductSearchRow toProductRow(
            SupplierProduct link,
            Item item,
            Supplier supplier,
            String imageUrl,
            String categoryName
    ) {
        BigDecimal price = link.getDefaultCostPrice() != null
                ? link.getDefaultCostPrice()
                : link.getLastCostPrice();
        return new PublicMarketplaceProductSearchRow(
                link.getId(),
                displayItemName(item),
                item.getBarcode(),
                link.getSupplierSku() != null && !link.getSupplierSku().isBlank()
                        ? link.getSupplierSku()
                        : item.getSku(),
                categoryName,
                imageUrl,
                link.getSupplierId(),
                supplier.getName(),
                blankToNull(supplier.getSupplierType()),
                link.getPackSize(),
                link.getPackUnit(),
                link.getMinOrderQty(),
                price,
                price != null ? "KES" : null,
                link.isActive());
    }

    private Map<String, Item> loadItems(Collection<String> itemIds) {
        Map<String, Item> out = new LinkedHashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        List<String> distinct = itemIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return out;
        }
        for (Item item : itemRepository.findAllById(distinct)) {
            out.put(item.getId(), item);
        }
        return out;
    }

    private Map<String, Supplier> loadSuppliers(Collection<String> supplierIds) {
        Map<String, Supplier> out = new LinkedHashMap<>();
        if (supplierIds == null || supplierIds.isEmpty()) {
            return out;
        }
        List<String> distinct = supplierIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return out;
        }
        for (Supplier supplier : supplierRepository.findAllById(distinct)) {
            out.put(supplier.getId(), supplier);
        }
        return out;
    }

    private Map<String, String> resolveThumbnailUrls(Collection<Item> items) {
        Map<String, String> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        List<String> itemIds = items.stream().map(Item::getId).filter(Objects::nonNull).distinct().toList();
        Map<String, String> gallery = firstGalleryImageUrlByItemId(itemIds);
        for (Item item : items) {
            String thumb = resolveListThumbnail(item, gallery);
            if (thumb != null && !thumb.isBlank() && item.getId() != null) {
                out.put(item.getId(), thumb);
            }
        }
        return out;
    }

    private Map<String, String> firstGalleryImageUrlByItemId(Collection<String> itemIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (itemIds == null || itemIds.isEmpty()) {
            return out;
        }
        Sort galleryOrder = Sort.by(Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<ItemImage> rows = itemImageRepository.findByItemIdIn(itemIds, galleryOrder);
        for (ItemImage img : rows) {
            String url = resolveImageRowPublicUrl(img);
            if (url == null || img.getItemId() == null) {
                continue;
            }
            out.putIfAbsent(img.getItemId(), url);
        }
        return out;
    }

    private static String resolveImageRowPublicUrl(ItemImage img) {
        String secure = img.getSecureUrl();
        if (secure != null && !secure.isBlank()) {
            return secure.trim();
        }
        String key = img.getS3Key();
        if (key != null) {
            String k = key.trim();
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return k;
            }
        }
        return null;
    }

    private static String resolveListThumbnail(Item i, Map<String, String> galleryFirstUrlByItemId) {
        String k = i.getImageKey();
        if (k != null && (k.startsWith("http://") || k.startsWith("https://"))) {
            return k.trim();
        }
        return galleryFirstUrlByItemId.get(i.getId());
    }

    private Map<String, String> categoryNamesById(Collection<String> categoryIds) {
        Map<String, String> out = new LinkedHashMap<>();
        if (categoryIds == null || categoryIds.isEmpty()) {
            return out;
        }
        List<String> distinct = categoryIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return out;
        }
        categoryRepository.findAllById(distinct).forEach(c -> out.put(c.getId(), c.getName()));
        return out;
    }

    private String businessName(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return null;
        }
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(b -> b.getName())
                .orElse(null);
    }

    private static List<String> buildTags(Supplier supplier, String businessName) {
        List<String> tags = new ArrayList<>();
        if (supplier.getSupplierType() != null && !supplier.getSupplierType().isBlank()) {
            tags.add(supplier.getSupplierType());
        }
        if (supplier.getPaymentMethodPreferred() != null && !supplier.getPaymentMethodPreferred().isBlank()) {
            tags.add(supplier.getPaymentMethodPreferred());
        }
        if (businessName != null && !businessName.isBlank()) {
            tags.add(businessName);
        }
        return tags;
    }

    private static String displayItemName(Item item) {
        if (item == null) {
            return "Product";
        }
        String name = item.getName();
        if (name != null && !name.isBlank() && !looksLikeUuid(name)) {
            if (item.getVariantName() != null && !item.getVariantName().isBlank()) {
                return name.trim() + " · " + item.getVariantName().trim();
            }
            return name.trim();
        }
        if (item.getVariantName() != null && !item.getVariantName().isBlank()) {
            return item.getVariantName().trim();
        }
        if (item.getBarcode() != null && !item.getBarcode().isBlank()) {
            return item.getBarcode().trim();
        }
        if (item.getSku() != null && !item.getSku().isBlank()) {
            return item.getSku().trim();
        }
        return "Product";
    }

    private static boolean looksLikeUuid(String value) {
        return value != null && value.matches(
                "(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static boolean hasContactInfo(SupplierContact c) {
        return (c.getName() != null && !c.getName().isBlank())
                || (c.getPhone() != null && !c.getPhone().isBlank())
                || (c.getEmail() != null && !c.getEmail().isBlank());
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
