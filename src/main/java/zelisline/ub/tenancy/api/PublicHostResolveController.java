package zelisline.ub.tenancy.api;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.application.PublicHostResolverService;

/**
 * Public tenant lookup by hostname. Used by the Next.js storefront so that any
 * mapped domain (tenant subdomain or custom domain) can drive {@code /shop}
 * without putting the slug in the URL path.
 *
 * <p>Tracks the same allow-list as other {@code /api/v1/public/**} endpoints in
 * {@code SecurityConfig}; shared rate limiting is applied by
 * {@code PublicStorefrontRateLimitFilter}.
 */
@RestController
@RequestMapping("/api/v1/public/host")
@RequiredArgsConstructor
public class PublicHostResolveController {

    private static final Duration CACHE_TTL = Duration.ofSeconds(60);

    private final PublicHostResolverService publicHostResolverService;

    @GetMapping("/resolve")
    public ResponseEntity<PublicHostResolveResponse> resolve(@RequestParam("host") String host) {
        PublicHostResolveResponse body = publicHostResolverService.resolveByHost(host)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active tenant mapping for host"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .header(HttpHeaders.VARY, "host")
                .body(body);
    }
}
