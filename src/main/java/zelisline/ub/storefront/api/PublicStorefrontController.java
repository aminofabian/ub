package zelisline.ub.storefront.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.GatewayStatus;
import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.repository.PaymentGatewayConfigRepository;
import zelisline.ub.storefront.api.dto.PublicCatalogItemDetailResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogListResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryListResponse;
import zelisline.ub.storefront.api.dto.PublicStorefrontResponse;
import zelisline.ub.storefront.application.PublicStorefrontCatalogService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}")
@RequiredArgsConstructor
public class PublicStorefrontController {

    private static final Logger log = LoggerFactory.getLogger(PublicStorefrontController.class);
    private static final int MAX_PAGE = 100;

    private final PublicStorefrontCatalogService publicStorefrontCatalogService;
    private final BusinessRepository businessRepository;
    private final PaymentGatewayConfigRepository configRepository;
    private final ObjectMapper objectMapper;

    @GetMapping("/storefront")
    public ResponseEntity<PublicStorefrontResponse> storefront(@PathVariable String slug) {
        PublicStorefrontResponse body = publicStorefrontCatalogService.getStorefront(slug);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(body);
    }

    @GetMapping("/catalog/categories")
    public ResponseEntity<PublicCategoryListResponse> categories(@PathVariable String slug) {
        PublicCategoryListResponse body = publicStorefrontCatalogService.listPublishedCategories(slug);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(body);
    }

    @GetMapping("/catalog/items")
    public ResponseEntity<PublicCatalogListResponse> listItems(
            @PathVariable String slug,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false, defaultValue = "24") int limit
    ) {
        int lim = Math.min(Math.max(limit, 1), MAX_PAGE);
        PublicCatalogListResponse body = publicStorefrontCatalogService.listItems(slug, q, categoryId, cursor, lim);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(body);
    }

    @GetMapping("/catalog/items/{id}")
    public ResponseEntity<PublicCatalogItemDetailResponse> itemDetail(
            @PathVariable String slug,
            @PathVariable String id
    ) {
        PublicCatalogItemDetailResponse body = publicStorefrontCatalogService.getItemDetail(slug, id);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(body);
    }

    @GetMapping("/catalog/items/by-barcode/{barcode}")
    public ResponseEntity<PublicCatalogItemDetailResponse> itemByBarcode(
            @PathVariable String slug,
            @PathVariable String barcode
    ) {
        PublicCatalogItemDetailResponse body = publicStorefrontCatalogService.getItemByBarcode(slug, barcode);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(60))).body(body);
    }

    @GetMapping("/payments/display-instructions")
    public List<DisplayInstructions> displayInstructions(@PathVariable String slug) {
        log.info("Display instructions requested for slug={}", slug);

        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug).orElse(null);
        if (business == null) {
            log.warn("Business not found for slug={}", slug);
            return List.of();
        }

        String businessId = business.getId();
        log.info("Resolved businessId={}", businessId);

        List<PaymentGatewayConfig> configs = configRepository
                .findByBusinessIdAndGatewayTypeAndStatus(businessId, GatewayType.MANUAL, GatewayStatus.ACTIVE);

        log.info("Found {} ACTIVE manual configs", configs.size());

        List<DisplayInstructions> result = new ArrayList<>();
        for (PaymentGatewayConfig cfg : configs) {
            String json = cfg.getDisplayInstructionsJson();
            if (json == null || json.isBlank()) continue;
            try {
                var node = objectMapper.readTree(json);
                result.add(new DisplayInstructions(
                        cfg.getId(),
                        node.has("type") ? node.get("type").asText() : null,
                        node.has("label") ? node.get("label").asText() : cfg.getLabel(),
                        node.has("instructions") ? node.get("instructions").asText() : null,
                        node.has("tillNumber") ? node.get("tillNumber").asText() : null,
                        node.has("businessNumber") ? node.get("businessNumber").asText() : null,
                        node.has("accountNumber") ? node.get("accountNumber").asText() : null,
                        node.has("bankName") ? node.get("bankName").asText() : null,
                        node.has("branchName") ? node.get("branchName").asText() : null,
                        node.has("accountName") ? node.get("accountName").asText() : null,
                        node.has("swiftCode") ? node.get("swiftCode").asText() : null
                ));
            } catch (JsonProcessingException ignored) {
            }
        }
        return result;
    }
}
