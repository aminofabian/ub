package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.CatalogSearchSupport;
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

    /**
     * Public product-name search for the barcode lookup page.
     * Uses the same {@link CatalogSearchSupport} ranking as cashier POS search
     * so compact queries like {@code cocacola} match {@code Coca-Cola}.
     */
    @Transactional(readOnly = true)
    public List<PublicBarcodeLookupResponse> searchByName(String q) {
        if (q == null || CatalogSearchSupport.isBlankQuery(q) || q.trim().length() < 2) {
            return List.of();
        }

        String query = q.trim();
        List<Item> ranked = List.of();
        for (String token : CatalogSearchSupport.dbCandidateTokens(query)) {
            // Keep only scannable hits before accepting a candidate set, so we
            // fall through to the next DB token when ranked rows lack barcodes.
            ranked = rankPublishedCandidates(token, query).stream()
                    .filter(i -> i.getBarcode() != null && !i.getBarcode().isBlank())
                    .toList();
            if (!ranked.isEmpty()) {
                break;
            }
        }

        if (ranked.size() > MAX_SEARCH_RESULTS) {
            ranked = ranked.subList(0, MAX_SEARCH_RESULTS);
        }

        // Resolve parent names for variants in one batch
        List<String> parentIds = ranked.stream()
                .map(Item::getVariantOfItemId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        var parentsById = itemRepository.findAllById(parentIds).stream()
                .collect(java.util.stream.Collectors.toMap(Item::getId, Item::getName));

        // Resolve business names, slugs, currencies, and prices — preserve relevance order
        List<PublicBarcodeLookupResponse> results = new ArrayList<>(ranked.size());
        for (Item item : ranked) {
            Business business = businessRepository
                    .findByIdAndDeletedAtIsNull(item.getBusinessId())
                    .orElse(null);
            if (business == null) {
                continue;
            }

            String catalogBranchId = resolveCatalogBranchId(business);

            BigDecimal price = null;
            if (catalogBranchId != null) {
                price = pricingService.getCurrentOpenSellingPrice(
                        business.getId(), item.getId(), catalogBranchId);
            }

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

            results.add(new PublicBarcodeLookupResponse(
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
                    null));
        }
        return results;
    }

    /**
     * Broad candidate fetch + cashier-style relevance ranking for one DB token.
     */
    private List<Item> rankPublishedCandidates(String dbToken, String query) {
        if (dbToken == null || dbToken.isBlank()) {
            return List.of();
        }
        String compact = compactForDb(dbToken);
        String qCompact = compact.length() >= 2 ? compact : null;

        List<Item> candidates = itemRepository.findPublishedByNameContaining(
                dbToken,
                qCompact,
                PageRequest.of(0, CatalogSearchSupport.CANDIDATE_FETCH_SIZE));

        return CatalogSearchSupport.rankAndFilter(
                candidates,
                item -> CatalogSearchSupport.SearchableText.of(
                        item.getName(),
                        item.getVariantName(),
                        item.getSku(),
                        item.getBarcode(),
                        item.getDescription(),
                        item.getBrand(),
                        item.getSize()),
                query);
    }

    /** Strip spaces and hyphens so SQL can recall {@code Coca-Cola} for {@code cocacola}. */
    private static String compactForDb(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
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
