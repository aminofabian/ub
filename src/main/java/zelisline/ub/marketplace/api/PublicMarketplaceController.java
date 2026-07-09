package zelisline.ub.marketplace.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.MarketplaceSupplierDetailResponse;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceProductSearchRow;
import zelisline.ub.marketplace.api.dto.PublicMarketplaceSupplierSearchRow;
import zelisline.ub.marketplace.application.PublicMarketplaceSearchService;

@RestController
@RequestMapping("/api/v1/public/marketplace")
@RequiredArgsConstructor
public class PublicMarketplaceController {

    private final PublicMarketplaceSearchService publicMarketplaceSearchService;

    @GetMapping("/suppliers/search")
    public Page<PublicMarketplaceSupplierSearchRow> searchSuppliers(
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return publicMarketplaceSearchService.searchSuppliers(q, pageable);
    }

    @GetMapping("/products/search")
    public Page<PublicMarketplaceProductSearchRow> searchProducts(
            @RequestParam(required = false) String q,
            Pageable pageable) {
        return publicMarketplaceSearchService.searchProducts(q, pageable);
    }

    /** Public storefront preview for an active tenant supplier. */
    @GetMapping("/suppliers/{id}")
    public MarketplaceSupplierDetailResponse getSupplier(@PathVariable String id) {
        return publicMarketplaceSearchService.getSupplierDetail(id);
    }
}
