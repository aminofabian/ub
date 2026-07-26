package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProductRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalProductResponse;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplierPriceOffer;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProduct;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductEditRequest;
import zelisline.ub.marketplace.domain.MarketplaceSupplierProductStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierPriceOfferRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductEditRequestRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierProductRepository;
import zelisline.ub.notifications.SupplierPortalNotificationTypes;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalCatalogService {

    private final MarketplaceSupplierProductRepository productRepository;
    private final MarketplaceSupplierPriceOfferRepository priceOfferRepository;
    private final MarketplaceSupplierProductEditRequestRepository editRequestRepository;
    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierPortalNotificationsService notificationsService;
    private final SupplierPortalShopLinkService shopLinkService;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public Page<SupplierPortalProductResponse> listProducts(
            String marketplaceSupplierId,
            String q,
            String status,
            Pageable pageable
    ) {
        Page<MarketplaceSupplierProduct> page = productRepository.searchForSupplier(
                marketplaceSupplierId,
                blankToNull(q),
                blankToNull(status),
                pageable);
        Map<String, String> images = resolveImageUrls(marketplaceSupplierId, page.getContent());
        return page.map(product -> toResponse(product, images.get(product.getId())));
    }

    /** List after healing shop links + importing shop-linked catalogue into marketplace products. */
    @Transactional
    public Page<SupplierPortalProductResponse> listProductsHealed(
            String marketplaceSupplierId,
            String q,
            String status,
            Pageable pageable
    ) {
        try {
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft heal — still return whatever catalogue exists.
        }
        return listProducts(marketplaceSupplierId, q, status, pageable);
    }

    @Transactional
    public SupplierPortalProductResponse createProduct(
            String marketplaceSupplierId,
            String supplierUserId,
            CreateSupplierPortalProductRequest request
    ) {
        requireProductEditsAllowed();
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        boolean needsApproval = settings.isRequireStoreApprovalProductEdits();

        MarketplaceSupplierProduct product = new MarketplaceSupplierProduct();
        product.setMarketplaceSupplierId(marketplaceSupplierId);
        applyProductFields(product, request.name(), request.barcode(), request.sku(),
                request.categoryName(), request.description(), request.packSize(),
                request.packUnit(), request.minOrderQty(),
                needsApproval
                        ? MarketplaceSupplierProductStatuses.INACTIVE
                        : MarketplaceSupplierProductStatuses.ACTIVE);
        productRepository.save(product);

        MarketplaceSupplierPriceOffer offer = new MarketplaceSupplierPriceOffer();
        offer.setMarketplaceSupplierId(marketplaceSupplierId);
        offer.setProductId(product.getId());
        offer.setPackageSize(defaultPackSize(request.packSize()));
        offer.setPackageUnit(defaultPackUnit(request.packUnit()));
        offer.setMinQty(BigDecimal.ONE);
        offer.setUnitPrice(request.unitPrice());
        offer.setCurrency(defaultCurrency(request.currency()));
        boolean requestedAvailable = request.available() == null || request.available();
        offer.setAvailable(!needsApproval && requestedAvailable);
        priceOfferRepository.save(offer);

        if (needsApproval) {
            queueActivationForNewProduct(product, supplierUserId, requestedAvailable);
        }
        return toResponse(product);
    }

    @Transactional
    public SupplierPortalProductResponse updateProduct(
            String marketplaceSupplierId,
            String supplierUserId,
            String productId,
            PatchSupplierPortalProductRequest request
    ) {
        requireProductEditsAllowed();
        MarketplaceSupplierProduct product = requireProduct(marketplaceSupplierId, productId);
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();

        if (settings.isRequireStoreApprovalProductEdits() && hasReviewableChange(request)) {
            return queueEdit(product, supplierUserId, request);
        }
        applyLivePatch(product, request);
        return toResponse(product);
    }

    @Transactional
    public void deleteProduct(
            String marketplaceSupplierId,
            String supplierUserId,
            String productId
    ) {
        requireProductEditsAllowed();
        MarketplaceSupplierProduct product = requireProduct(marketplaceSupplierId, productId);
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        if (settings.isRequireStoreApprovalProductEdits()
                && MarketplaceSupplierProductStatuses.ACTIVE.equals(product.getStatus())) {
            queueEdit(product, supplierUserId, new PatchSupplierPortalProductRequest(
                    null, null, null, null, null, null, null, null, null, null,
                    Boolean.FALSE,
                    MarketplaceSupplierProductStatuses.INACTIVE));
            return;
        }
        product.setStatus(MarketplaceSupplierProductStatuses.INACTIVE);
        productRepository.save(product);
        for (MarketplaceSupplierPriceOffer offer : priceOfferRepository.findByProductId(product.getId())) {
            offer.setAvailable(false);
            priceOfferRepository.save(offer);
        }
    }

    @Transactional
    public void applyProposedEdit(MarketplaceSupplierProductEditRequest edit) {
        MarketplaceSupplierProduct product = requireProduct(edit.getMarketplaceSupplierId(), edit.getProductId());
        Map<String, Object> proposed = readMap(edit.getProposedJson());
        PatchSupplierPortalProductRequest patch = mapToPatch(proposed);
        applyLivePatch(product, patch);
    }

    @Transactional
    public void notifyEditDecision(MarketplaceSupplierProductEditRequest edit, boolean approved) {
        MarketplaceSupplierProduct product = productRepository.findById(edit.getProductId()).orElse(null);
        String name = product == null ? "product" : product.getName();
        String title = approved ? "Product edit approved" : "Product edit rejected";
        String body = approved
                ? "Your change to \"" + name + "\" is now live."
                : "Your change to \"" + name + "\" was rejected"
                        + (edit.getReviewNote() != null && !edit.getReviewNote().isBlank()
                        ? ": " + edit.getReviewNote().trim()
                        : ".");
        notificationsService.create(
                edit.getMarketplaceSupplierId(),
                approved
                        ? SupplierPortalNotificationTypes.PRODUCT_APPROVED
                        : SupplierPortalNotificationTypes.PRODUCT_REJECTED,
                title,
                body,
                "/supplier-portal/catalog");
    }

    private void queueActivationForNewProduct(
            MarketplaceSupplierProduct product,
            String supplierUserId,
            boolean requestedAvailable
    ) {
        Map<String, Object> proposed = new LinkedHashMap<>();
        proposed.put("status", MarketplaceSupplierProductStatuses.ACTIVE);
        proposed.put("available", requestedAvailable);

        MarketplaceSupplierProductEditRequest edit = new MarketplaceSupplierProductEditRequest();
        edit.setMarketplaceSupplierId(product.getMarketplaceSupplierId());
        edit.setProductId(product.getId());
        edit.setRequestedByUserId(supplierUserId);
        edit.setStatus(MarketplaceSupplierProductEditRequest.PENDING);
        edit.setProposedJson(writeJson(proposed));
        edit.setLiveSnapshotJson(writeJson(liveSnapshot(product)));
        editRequestRepository.save(edit);
    }

    private SupplierPortalProductResponse queueEdit(
            MarketplaceSupplierProduct product,
            String supplierUserId,
            PatchSupplierPortalProductRequest request
    ) {
        editRequestRepository.findFirstByProductIdAndStatusOrderByCreatedAtDesc(
                        product.getId(), MarketplaceSupplierProductEditRequest.PENDING)
                .ifPresent(existing -> {
                    existing.setStatus(MarketplaceSupplierProductEditRequest.REJECTED);
                    existing.setReviewNote("Superseded by a newer edit request");
                    existing.setReviewedAt(Instant.now());
                    editRequestRepository.save(existing);
                });

        Map<String, Object> proposed = proposedFromPatch(request);
        if (proposed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No editable fields in request");
        }

        MarketplaceSupplierProductEditRequest edit = new MarketplaceSupplierProductEditRequest();
        edit.setMarketplaceSupplierId(product.getMarketplaceSupplierId());
        edit.setProductId(product.getId());
        edit.setRequestedByUserId(supplierUserId);
        edit.setStatus(MarketplaceSupplierProductEditRequest.PENDING);
        edit.setProposedJson(writeJson(proposed));
        edit.setLiveSnapshotJson(writeJson(liveSnapshot(product)));
        editRequestRepository.save(edit);

        // Non-price metadata can still update immediately when not in proposed? Spec says pending
        // cannot change live price — keep all reviewable fields pending only.
        return toResponse(product);
    }

    private void applyLivePatch(MarketplaceSupplierProduct product, PatchSupplierPortalProductRequest request) {
        if (request.name() != null) {
            if (request.name().isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be empty");
            }
            product.setName(request.name().trim());
        }
        if (request.barcode() != null) {
            product.setBarcode(blankToNull(request.barcode()));
        }
        if (request.sku() != null) {
            product.setSku(blankToNull(request.sku()));
        }
        if (request.categoryName() != null) {
            product.setCategoryName(blankToNull(request.categoryName()));
        }
        if (request.description() != null) {
            product.setDescription(blankToNull(request.description()));
        }
        if (request.packSize() != null) {
            product.setPackSize(request.packSize());
        }
        if (request.packUnit() != null) {
            product.setPackUnit(blankToNull(request.packUnit()));
        }
        if (request.minOrderQty() != null) {
            product.setMinOrderQty(request.minOrderQty());
        }
        if (request.status() != null) {
            String status = request.status().trim().toLowerCase();
            if (!MarketplaceSupplierProductStatuses.ACTIVE.equals(status)
                    && !MarketplaceSupplierProductStatuses.INACTIVE.equals(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status must be active or inactive");
            }
            product.setStatus(status);
        }
        productRepository.save(product);

        if (request.unitPrice() != null || request.currency() != null || request.available() != null
                || request.packSize() != null || request.packUnit() != null) {
            MarketplaceSupplierPriceOffer offer = currentPrimaryOffer(product.getId())
                    .orElseGet(() -> {
                        MarketplaceSupplierPriceOffer created = new MarketplaceSupplierPriceOffer();
                        created.setMarketplaceSupplierId(product.getMarketplaceSupplierId());
                        created.setProductId(product.getId());
                        created.setPackageSize(defaultPackSize(product.getPackSize()));
                        created.setPackageUnit(defaultPackUnit(product.getPackUnit()));
                        created.setMinQty(BigDecimal.ONE);
                        created.setEffectiveFrom(Instant.now());
                        return created;
                    });
            if (request.packSize() != null) {
                offer.setPackageSize(request.packSize());
            }
            if (request.packUnit() != null) {
                offer.setPackageUnit(defaultPackUnit(request.packUnit()));
            }
            if (request.unitPrice() != null) {
                offer.setUnitPrice(request.unitPrice());
            }
            if (request.currency() != null) {
                offer.setCurrency(defaultCurrency(request.currency()));
            }
            if (request.available() != null) {
                offer.setAvailable(request.available());
            }
            priceOfferRepository.save(offer);
        }
    }

    private void requireProductEditsAllowed() {
        PlatformSupplierPortalSettings settings = portalSettingsService.loadSingleton();
        if (!settings.isAllowProductEdits()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Product edits are disabled by platform settings");
        }
    }

    private static boolean hasReviewableChange(PatchSupplierPortalProductRequest request) {
        return request.name() != null
                || request.barcode() != null
                || request.sku() != null
                || request.categoryName() != null
                || request.description() != null
                || request.unitPrice() != null
                || request.currency() != null
                || request.packSize() != null
                || request.packUnit() != null
                || request.minOrderQty() != null
                || request.available() != null
                || request.status() != null;
    }

    private Map<String, Object> proposedFromPatch(PatchSupplierPortalProductRequest request) {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfPresent(map, "name", request.name());
        putIfPresent(map, "barcode", request.barcode());
        putIfPresent(map, "sku", request.sku());
        putIfPresent(map, "categoryName", request.categoryName());
        putIfPresent(map, "description", request.description());
        putIfPresent(map, "packSize", request.packSize());
        putIfPresent(map, "packUnit", request.packUnit());
        putIfPresent(map, "minOrderQty", request.minOrderQty());
        putIfPresent(map, "unitPrice", request.unitPrice());
        putIfPresent(map, "currency", request.currency());
        putIfPresent(map, "status", request.status());
        if (request.available() != null) {
            map.put("available", request.available());
        }
        return map;
    }

    private Map<String, Object> liveSnapshot(MarketplaceSupplierProduct product) {
        MarketplaceSupplierPriceOffer offer = currentPrimaryOffer(product.getId()).orElse(null);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", product.getName());
        map.put("barcode", product.getBarcode());
        map.put("sku", product.getSku());
        map.put("categoryName", product.getCategoryName());
        map.put("description", product.getDescription());
        map.put("packSize", product.getPackSize());
        map.put("packUnit", product.getPackUnit());
        map.put("minOrderQty", product.getMinOrderQty());
        map.put("status", product.getStatus());
        map.put("unitPrice", offer == null ? null : offer.getUnitPrice());
        map.put("currency", offer == null ? null : offer.getCurrency());
        map.put("available", offer != null && offer.isAvailable());
        return map;
    }

    private PatchSupplierPortalProductRequest mapToPatch(Map<String, Object> proposed) {
        return new PatchSupplierPortalProductRequest(
                asString(proposed.get("name")),
                asString(proposed.get("barcode")),
                asString(proposed.get("sku")),
                asString(proposed.get("categoryName")),
                asString(proposed.get("description")),
                asDecimal(proposed.get("packSize")),
                asString(proposed.get("packUnit")),
                asDecimal(proposed.get("minOrderQty")),
                asDecimal(proposed.get("unitPrice")),
                asString(proposed.get("currency")),
                asBoolean(proposed.get("available")),
                asString(proposed.get("status")));
    }

    private MarketplaceSupplierProduct requireProduct(String marketplaceSupplierId, String productId) {
        return productRepository.findByIdAndMarketplaceSupplierId(productId, marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
    }

    private java.util.Optional<MarketplaceSupplierPriceOffer> currentPrimaryOffer(String productId) {
        List<MarketplaceSupplierPriceOffer> offers =
                priceOfferRepository.findCurrentOffers(productId, Instant.now());
        return offers.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(offers.get(0));
    }

    private SupplierPortalProductResponse toResponse(MarketplaceSupplierProduct product) {
        return toResponse(product, null);
    }

    private SupplierPortalProductResponse toResponse(MarketplaceSupplierProduct product, String imageUrl) {
        MarketplaceSupplierPriceOffer offer = currentPrimaryOffer(product.getId()).orElse(null);
        var pending = editRequestRepository.findFirstByProductIdAndStatusOrderByCreatedAtDesc(
                product.getId(), MarketplaceSupplierProductEditRequest.PENDING);
        return new SupplierPortalProductResponse(
                product.getId(),
                product.getName(),
                product.getBarcode(),
                product.getSku(),
                product.getCategoryName(),
                product.getDescription(),
                product.getPackSize(),
                product.getPackUnit(),
                product.getMinOrderQty(),
                offer == null ? null : offer.getUnitPrice(),
                offer == null ? null : offer.getCurrency(),
                offer != null && offer.isAvailable(),
                product.getStatus(),
                product.getVersion(),
                product.getCreatedAt(),
                product.getUpdatedAt(),
                pending.map(MarketplaceSupplierProductEditRequest::getId).orElse(null),
                pending.map(p -> readMap(p.getProposedJson())).orElse(null),
                blankToNull(imageUrl));
    }

    /**
     * Prefer shop catalog photos (linked items) matched by barcode, then by product name.
     */
    private Map<String, String> resolveImageUrls(
            String marketplaceSupplierId,
            List<MarketplaceSupplierProduct> products
    ) {
        Map<String, String> out = new HashMap<>();
        if (products == null || products.isEmpty()) {
            return out;
        }
        List<BusinessSupplierConnection> links = connectionRepository.findByMarketplaceSupplierIdAndStatus(
                marketplaceSupplierId, BusinessSupplierConnectionStatuses.ACTIVE);
        if (links.isEmpty()) {
            return out;
        }

        List<SupplierProduct> shopLinks = new ArrayList<>();
        for (BusinessSupplierConnection link : links) {
            if (link.getLocalSupplierId() == null) {
                continue;
            }
            shopLinks.addAll(supplierProductRepository.listActivePublicForSupplier(link.getLocalSupplierId()));
        }
        if (shopLinks.isEmpty()) {
            return out;
        }

        List<String> itemIds = shopLinks.stream()
                .map(SupplierProduct::getItemId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (itemIds.isEmpty()) {
            return out;
        }

        Map<String, Item> itemsById = new HashMap<>();
        for (Item item : itemRepository.findAllById(itemIds)) {
            if (item.getId() != null) {
                itemsById.put(item.getId(), item);
            }
        }
        Map<String, String> thumbs = thumbnailsForItems(itemsById.values());

        Map<String, String> byBarcode = new HashMap<>();
        Map<String, String> byName = new HashMap<>();
        for (Item item : itemsById.values()) {
            String thumb = thumbs.get(item.getId());
            if (thumb == null || thumb.isBlank()) {
                continue;
            }
            if (item.getBarcode() != null && !item.getBarcode().isBlank()) {
                byBarcode.putIfAbsent(item.getBarcode().trim().toLowerCase(Locale.ROOT), thumb);
            }
            if (item.getName() != null && !item.getName().isBlank()) {
                byName.putIfAbsent(item.getName().trim().toLowerCase(Locale.ROOT), thumb);
            }
        }

        for (MarketplaceSupplierProduct product : products) {
            String image = null;
            if (product.getBarcode() != null && !product.getBarcode().isBlank()) {
                image = byBarcode.get(product.getBarcode().trim().toLowerCase(Locale.ROOT));
            }
            if (image == null && product.getName() != null) {
                image = byName.get(product.getName().trim().toLowerCase(Locale.ROOT));
            }
            if (image != null) {
                out.put(product.getId(), image);
            }
        }
        return out;
    }

    private Map<String, String> thumbnailsForItems(Collection<Item> items) {
        Map<String, String> out = new LinkedHashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        List<String> itemIds = items.stream().map(Item::getId).filter(Objects::nonNull).distinct().toList();
        Sort galleryOrder = Sort.by(Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        Map<String, String> gallery = new LinkedHashMap<>();
        for (ItemImage img : itemImageRepository.findByItemIdIn(itemIds, galleryOrder)) {
            String url = resolveImageRowPublicUrl(img);
            if (url != null && img.getItemId() != null) {
                gallery.putIfAbsent(img.getItemId(), url);
            }
        }
        for (Item item : items) {
            if (item.getId() == null) {
                continue;
            }
            String key = item.getImageKey();
            if (key != null && (key.startsWith("http://") || key.startsWith("https://"))) {
                out.put(item.getId(), key.trim());
                continue;
            }
            String galleryUrl = gallery.get(item.getId());
            if (galleryUrl != null) {
                out.put(item.getId(), galleryUrl);
            }
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

    private static void applyProductFields(
            MarketplaceSupplierProduct product,
            String name,
            String barcode,
            String sku,
            String categoryName,
            String description,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            String status
    ) {
        product.setName(name.trim());
        product.setBarcode(blankToNull(barcode));
        product.setSku(blankToNull(sku));
        product.setCategoryName(blankToNull(categoryName));
        product.setDescription(blankToNull(description));
        product.setPackSize(packSize);
        product.setPackUnit(blankToNull(packUnit));
        product.setMinOrderQty(minOrderQty);
        product.setStatus(status);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not serialize edit payload");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        return new BigDecimal(String.valueOf(value));
    }

    private static Boolean asBoolean(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static BigDecimal defaultPackSize(BigDecimal packSize) {
        return packSize == null ? BigDecimal.ONE : packSize;
    }

    private static String defaultPackUnit(String packUnit) {
        String unit = blankToNull(packUnit);
        return unit == null ? "each" : unit;
    }

    private static String defaultCurrency(String currency) {
        String c = blankToNull(currency);
        return c == null ? "KES" : c.toUpperCase();
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
