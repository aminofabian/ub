package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.sales.application.VariableWeightBarcodeConfig;
import zelisline.ub.sales.application.VariableWeightBarcodeParseResult;
import zelisline.ub.sales.application.VariableWeightBarcodeParser;
import zelisline.ub.storefront.api.dto.PublicBarcodeLookupResponse;
import zelisline.ub.storefront.api.dto.PublicItemImageResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PublicBarcodeLookupService {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final BusinessRepository businessRepository;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public PublicBarcodeLookupResponse lookup(String barcode) {
        VariableWeightBarcodeConfig vwConfig = VariableWeightBarcodeConfig.standardEnabled();
        var parsed = VariableWeightBarcodeParser.parse(barcode, vwConfig);
        if (parsed.isPresent()) {
            return lookupVariableWeightPublished(parsed.get());
        }

        String normalized = ItemCatalogService.normalizeBarcode(barcode);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }

        Item item = itemRepository
                .findFirstPublishedByBarcode(normalized)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        Business business = businessRepository.findByIdAndDeletedAtIsNull(item.getBusinessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        // Try to resolve the storefront catalog branch for pricing
        String catalogBranchId = null;
        String rawSettings = business.getSettings();
        if (rawSettings != null && !rawSettings.isBlank()) {
            try {
                JsonNode root = OBJECT_MAPPER.readTree(rawSettings);
                JsonNode sfNode = root.get("storefront");
                if (sfNode != null && sfNode.isObject()) {
                    JsonNode enabled = sfNode.get("enabled");
                    JsonNode branchId = sfNode.get("catalogBranchId");
                    if (enabled != null && enabled.asBoolean(false)
                            && branchId != null && !branchId.asText().isBlank()) {
                        catalogBranchId = branchId.asText();
                    }
                }
            } catch (Exception ignored) {
                // settings JSON is malformed — skip pricing
            }
        }

        BigDecimal price = null;
        if (catalogBranchId != null) {
            price = pricingService.getCurrentOpenSellingPrice(
                    business.getId(), item.getId(), catalogBranchId);
        }

        List<PublicItemImageResponse> images = new ArrayList<>();
        List<ItemImage> imgs = itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId());
        for (ItemImage img : imgs) {
            String url = resolveImagePublicUrl(img);
            if (url != null) {
                images.add(new PublicItemImageResponse(
                        url,
                        img.getAltText() != null && !img.getAltText().isBlank()
                                ? img.getAltText().trim() : null,
                        img.getWidth(),
                        img.getHeight()));
            }
        }

        String parentName = null;
        String variantLabel = null;
        if (item.getVariantOfItemId() != null) {
            Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(
                    item.getVariantOfItemId(), item.getBusinessId()).orElse(null);
            if (parent != null) {
                parentName = parent.getName();
                variantLabel = item.getVariantName();
            }
        }

        return new PublicBarcodeLookupResponse(
                item.getId(),
                item.getSku(),
                item.getBarcode(),
                item.getName(),
                item.getDescription() != null && !item.getDescription().isBlank()
                        ? item.getDescription().trim() : null,
                item.getBrand() != null && !item.getBrand().isBlank()
                        ? item.getBrand().trim() : null,
                item.getSize() != null && !item.getSize().isBlank()
                        ? item.getSize().trim() : null,
                business.getName(),
                business.getSlug(),
                business.getCurrency(),
                price,
                null,
                images,
                parentName,
                variantLabel,
                null,
                null,
                null);
    }

    private PublicBarcodeLookupResponse lookupVariableWeightPublished(
            VariableWeightBarcodeParseResult parsed
    ) {
        Item item = itemRepository.findFirstPublishedByPluCode(parsed.pluCode())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Unknown PLU " + parsed.pluCode()));
        if (!item.isWeighed()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "PLU " + parsed.pluCode() + " is not a weighed item");
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(item.getBusinessId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        String catalogBranchId = resolveCatalogBranchId(business);
        BigDecimal price = null;
        if (catalogBranchId != null) {
            price = pricingService.getCurrentOpenSellingPrice(
                    business.getId(), item.getId(), catalogBranchId);
        }
        if (price == null) {
            price = item.getBundlePrice();
        }

        BigDecimal parsedWeightKg = parsed.embeddedWeightKg();
        BigDecimal parsedLineTotal = parsed.embeddedPrice();
        if (parsed.embeddedField() == VariableWeightBarcodeConfig.EmbeddedField.WEIGHT
                && parsedWeightKg != null
                && price != null) {
            parsedLineTotal = parsedWeightKg.multiply(price);
        }

        List<PublicItemImageResponse> images = new ArrayList<>();
        List<ItemImage> imgs = itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId());
        for (ItemImage img : imgs) {
            String url = resolveImagePublicUrl(img);
            if (url != null) {
                images.add(new PublicItemImageResponse(
                        url,
                        img.getAltText() != null && !img.getAltText().isBlank()
                                ? img.getAltText().trim() : null,
                        img.getWidth(),
                        img.getHeight()));
            }
        }

        return new PublicBarcodeLookupResponse(
                item.getId(),
                item.getSku(),
                item.getBarcode(),
                item.getName(),
                item.getDescription() != null && !item.getDescription().isBlank()
                        ? item.getDescription().trim() : null,
                item.getBrand() != null && !item.getBrand().isBlank()
                        ? item.getBrand().trim() : null,
                item.getSize() != null && !item.getSize().isBlank()
                        ? item.getSize().trim() : null,
                business.getName(),
                business.getSlug(),
                business.getCurrency(),
                price,
                null,
                images,
                null,
                null,
                parsed.pluCode(),
                parsedWeightKg,
                parsedLineTotal);
    }

    private static final int MAX_SEARCH_RESULTS = 25;

    @Transactional(readOnly = true)
    public List<PublicBarcodeLookupResponse> searchByName(String q) {
        if (q == null || q.isBlank() || q.trim().length() < 2) {
            return List.of();
        }

        String query = q.trim();
        String queryNoSpace = query.replace(" ", "");
        // Only pass the space-stripped variant if it differs from the original
        String qNoSpace = queryNoSpace.equals(query) ? null : queryNoSpace;

        List<Item> items = itemRepository.findPublishedByNameContaining(
                query, qNoSpace, PageRequest.of(0, MAX_SEARCH_RESULTS));

        // Only return items that have a barcode — this is a barcode lookup tool.
        // The query intentionally casts a wider net (no barcode filter in SQL) so
        // it can match more rows; we post-filter here to keep results scannable.
        items = items.stream()
                .filter(i -> i.getBarcode() != null && !i.getBarcode().isBlank())
                .toList();

        // Resolve parent names for variants in one batch
        List<String> parentIds = items.stream()
                .map(Item::getVariantOfItemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        var parentsById = itemRepository.findAllById(parentIds).stream()
                .collect(java.util.stream.Collectors.toMap(Item::getId, Item::getName));

        // Resolve business names, slugs, currencies, and prices
        return items.stream()
                .map(item -> {
                    Business business = businessRepository
                            .findByIdAndDeletedAtIsNull(item.getBusinessId())
                            .orElse(null);
                    if (business == null) {
                        return null;
                    }

                    // Try to resolve the storefront catalog branch for pricing
                    String catalogBranchId = resolveCatalogBranchId(business);

                    BigDecimal price = null;
                    if (catalogBranchId != null) {
                        price = pricingService.getCurrentOpenSellingPrice(
                                business.getId(), item.getId(), catalogBranchId);
                    }

                    // First image only for search results
                    PublicItemImageResponse image = null;
                    List<ItemImage> imgs = itemImageRepository
                            .findByItemIdOrderBySortOrderAscIdAsc(item.getId());
                    if (!imgs.isEmpty()) {
                        String url = resolveImagePublicUrl(imgs.get(0));
                        if (url != null) {
                            image = new PublicItemImageResponse(
                                    url,
                                    imgs.get(0).getAltText() != null && !imgs.get(0).getAltText().isBlank()
                                            ? imgs.get(0).getAltText().trim() : null,
                                    imgs.get(0).getWidth(),
                                    imgs.get(0).getHeight());
                        }
                    }

                    List<PublicItemImageResponse> images = image != null
                            ? List.of(image)
                            : List.of();

                    String parentName = null;
                    String variantLabel = null;
                    if (item.getVariantOfItemId() != null) {
                        parentName = parentsById.get(item.getVariantOfItemId());
                        variantLabel = item.getVariantName();
                    }

                    return new PublicBarcodeLookupResponse(
                            item.getId(),
                            item.getSku(),
                            item.getBarcode(),
                            parentName != null ? parentName : item.getName(),
                            item.getDescription() != null && !item.getDescription().isBlank()
                                    ? item.getDescription().trim() : null,
                            item.getBrand() != null && !item.getBrand().isBlank()
                                    ? item.getBrand().trim() : null,
                            item.getSize() != null && !item.getSize().isBlank()
                                    ? item.getSize().trim() : null,
                            business.getName(),
                            business.getSlug(),
                            business.getCurrency(),
                            price,
                            null,
                            images,
                            parentName,
                            variantLabel,
                            null,
                            null,
                            null);
                })
                .filter(r -> r != null)
                .sorted(Comparator.comparing(PublicBarcodeLookupResponse::name))
                .toList();
    }

    private static String resolveCatalogBranchId(Business business) {
        String rawSettings = business.getSettings();
        if (rawSettings == null || rawSettings.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(rawSettings);
            JsonNode sfNode = root.get("storefront");
            if (sfNode != null && sfNode.isObject()) {
                JsonNode enabled = sfNode.get("enabled");
                JsonNode branchId = sfNode.get("catalogBranchId");
                if (enabled != null && enabled.asBoolean(false)
                        && branchId != null && !branchId.asText().isBlank()) {
                    return branchId.asText();
                }
            }
        } catch (Exception ignored) {
            // settings JSON is malformed — skip pricing
        }
        return null;
    }

    private static String resolveImagePublicUrl(ItemImage img) {
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
}
