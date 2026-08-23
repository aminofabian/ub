package zelisline.ub.tenancy.api;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;
import zelisline.ub.tenancy.application.PublicShopsSearchService;

/**
 * Public shop directory search for the apex "one door" sheet (Phase 4, §13).
 *
 * <p>Returns public directory data only (slug, name, logo, tenant-owned host) —
 * never phone numbers, sales, or anything shopper-specific — capped server-side
 * and rate-limited per IP by {@code PublicStorefrontRateLimitFilter} (which
 * covers every {@code GET /api/v1/public/**}).
 */
@RestController
@RequestMapping("/api/v1/public/shops")
@RequiredArgsConstructor
public class PublicShopsSearchController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final PublicShopsSearchService publicShopsSearchService;

    @GetMapping("/search")
    public ResponseEntity<List<PublicShopsSearchResponse>> search(
            @RequestParam("q") String query) {
        List<PublicShopsSearchResponse> body = publicShopsSearchService.search(query);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(body);
    }
}
