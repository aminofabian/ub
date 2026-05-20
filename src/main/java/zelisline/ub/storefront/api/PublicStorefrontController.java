package zelisline.ub.storefront.api;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.storefront.api.dto.PublicCatalogItemDetailResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogListResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryListResponse;
import zelisline.ub.storefront.api.dto.PublicCheckoutPaymentOptions;
import zelisline.ub.storefront.api.dto.PublicStorefrontResponse;
import zelisline.ub.storefront.application.PublicStorefrontCatalogService;
import zelisline.ub.storefront.application.PublicStorefrontPaymentService;

@RestController
@RequestMapping("/api/v1/public/businesses/{slug}")
@RequiredArgsConstructor
public class PublicStorefrontController {

    private static final int MAX_PAGE = 100;

    private final PublicStorefrontCatalogService publicStorefrontCatalogService;
    private final PublicStorefrontPaymentService publicStorefrontPaymentService;

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

    @GetMapping("/payments/checkout-options")
    public ResponseEntity<PublicCheckoutPaymentOptions> checkoutPaymentOptions(@PathVariable String slug) {
        PublicCheckoutPaymentOptions body = publicStorefrontPaymentService.checkoutOptions(slug);
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofSeconds(30))).body(body);
    }

    /** @deprecated Prefer {@link #checkoutPaymentOptions}; returns manual instructions only. */
    @GetMapping("/payments/display-instructions")
    public List<DisplayInstructions> displayInstructions(@PathVariable String slug) {
        return publicStorefrontPaymentService.checkoutOptions(slug).manual();
    }
}
