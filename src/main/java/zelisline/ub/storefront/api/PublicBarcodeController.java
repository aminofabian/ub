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
import zelisline.ub.storefront.api.dto.PublicBarcodeLookupResponse;
import zelisline.ub.storefront.application.PublicBarcodeLookupService;

/**
 * Standalone public barcode lookup — no tenant, no slug, no auth.
 *
 * <pre>
 *   GET /api/v1/public/barcode/6164000012345
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/public/barcode")
@RequiredArgsConstructor
public class PublicBarcodeController {

    private final PublicBarcodeLookupService lookupService;

    @GetMapping("/{barcode}")
    public ResponseEntity<PublicBarcodeLookupResponse> lookup(@PathVariable String barcode) {
        PublicBarcodeLookupResponse body = lookupService.lookup(barcode);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(60)))
                .body(body);
    }

    /**
     * Search published products by name across all businesses.
     * <pre>{@code GET /api/v1/public/barcode/search?q=coffee&limit=20}</pre>
     */
    @GetMapping("/search")
    public ResponseEntity<List<PublicBarcodeLookupResponse>> search(
            @RequestParam String q,
            @RequestParam(required = false, defaultValue = "20") int limit
    ) {
        List<PublicBarcodeLookupResponse> results = lookupService.searchByName(q);
        int capped = Math.min(results.size(), Math.max(1, Math.min(limit, 25)));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(120)))
                .body(results.subList(0, capped));
    }
}
