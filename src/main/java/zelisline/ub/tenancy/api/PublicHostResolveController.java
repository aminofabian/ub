package zelisline.ub.tenancy.api;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.OnboardBusinessRequest;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.PublicSignInDestinationResponse;
import zelisline.ub.tenancy.api.dto.SelfServeCountryResponse;
import zelisline.ub.tenancy.application.PublicHostResolverService;
import zelisline.ub.tenancy.application.PublicSignInDestinationService;
import zelisline.ub.tenancy.application.RegionDefaults;

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
    private final PublicSignInDestinationService publicSignInDestinationService;
    private final RegionDefaults regionDefaults;

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

    /**
     * Email-based tenant lookup so visitors on the landing page can be
     * redirected to their correct business subdomain for login.
     */
    @GetMapping("/resolve-by-email")
    public ResponseEntity<PublicHostResolveResponse> resolveByEmail(
            @RequestParam("email") String email) {
        PublicHostResolveResponse body = publicHostResolverService.resolveByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active business found for this email"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(body);
    }

    /**
     * Apex sign-in: every shop/portal tied to an email, or the supplier door
     * tied to a phone (phone does not list shops — those need an SMS proof).
     * Empty list when unknown.
     */
    @GetMapping("/sign-in-destinations")
    public ResponseEntity<List<PublicSignInDestinationResponse>> signInDestinations(
            @RequestParam(value = "email", required = false) String email,
            @RequestParam(value = "phone", required = false) String phone) {
        List<PublicSignInDestinationResponse> body;
        if (email != null && !email.isBlank()) {
            body = publicSignInDestinationService.byEmail(email);
        } else if (phone != null && !phone.isBlank()) {
            body = publicSignInDestinationService.byPhone(phone);
        } else {
            body = List.of();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    /**
     * Shop finder for the landing page — resolve by business name, slug, or host.
     */
    @GetMapping("/resolve-by-shop")
    public ResponseEntity<PublicHostResolveResponse> resolveByShop(
            @RequestParam("q") String query) {
        PublicHostResolveResponse body = publicHostResolverService.resolveByShopQuery(query)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No active shop found for that name"));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(body);
    }

    /**
     * Countries enabled for cloud self-serve onboarding (picker source of truth).
     */
    @GetMapping("/selfserve-countries")
    public ResponseEntity<List<SelfServeCountryResponse>> selfServeCountries() {
        List<SelfServeCountryResponse> body = regionDefaults.selfServeProfiles().stream()
                .map(regionDefaults::toSelfServeCountry)
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(CACHE_TTL).cachePublic())
                .body(body);
    }

    /**
     * Self-service business onboarding for visitors on an unmapped domain.
     * Creates a business, maps the host, and returns tenant context so the
     * frontend can proceed directly to login or signup.
     */
    @PostMapping("/onboard")
    public ResponseEntity<PublicHostResolveResponse> onboard(
            @Valid @RequestBody OnboardBusinessRequest request) {
        PublicHostResolveResponse body = publicHostResolverService.onboardBusiness(
                request.name(), request.host(), request.countryCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(body);
    }
}
