package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;
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
        if (barcode == null || barcode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }

        Item item = itemRepository
                .findFirstPublishedByBarcode(barcode.trim())
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
                images);
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
