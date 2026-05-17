package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.catalog.application.CatalogBootstrapService;

/**
 * Maps a browser hostname to its tenant configuration using the same active
 * domain mapping that {@code DomainBusinessResolverFilter} consults for
 * authenticated traffic. Exposed over an unauthenticated endpoint so the
 * Next.js frontend can drive UI, auth flows, branding, and feature gates
 * from a single resolve call per (host x cache-window).
 */
@Service
public class PublicHostResolverService {

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    private final DomainMappingRepository domainMappingRepository;
    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final CatalogBootstrapService catalogBootstrapService;

    public PublicHostResolverService(
            DomainMappingRepository domainMappingRepository,
            BusinessRepository businessRepository,
            StorefrontSettingsService storefrontSettingsService,
            CatalogBootstrapService catalogBootstrapService) {
        this.domainMappingRepository = domainMappingRepository;
        this.businessRepository = businessRepository;
        this.storefrontSettingsService = storefrontSettingsService;
        this.catalogBootstrapService = catalogBootstrapService;
    }

    @Transactional(readOnly = true)
    public Optional<PublicHostResolveResponse> resolveByHost(String rawHost) {
        String lookup = TenantHostParsing.hostnameOnly(rawHost);
        if (lookup == null) {
            return Optional.empty();
        }
        return domainMappingRepository.findByDomainAndActiveTrue(lookup)
                .map(DomainMapping::getBusinessId)
                .flatMap(businessRepository::findByIdAndDeletedAtIsNull)
                .map(this::toResponse);
    }

    /**
     * Self-service onboarding: creates a business for an unmapped host so the
     * visitor can immediately proceed to login or signup.
     *
     * <p>The business slug is derived from the provided name (lowercased,
     * whitespace→hyphen, non-alphanumeric stripped). A domain mapping is
     * created for the host; if the host matches the platform apex, an
     * additional {@code {slug}.{suffix}} mapping is also created so the
     * tenant gets its own subdomain.
     *
     * @param name  display name for the new business
     * @param rawHost  the host the visitor is currently on
     * @return full resolve response for the newly created tenant
     */
    @Transactional
    public PublicHostResolveResponse onboardBusiness(String name, String rawHost) {
        String lookup = TenantHostParsing.hostnameOnly(rawHost);
        if (lookup == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A valid host is required");
        }

        // If this host already maps to a business, reject (idempotent guard).
        if (domainMappingRepository.findByDomainAndActiveTrue(lookup).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "A business is already registered for this host");
        }

        String slug = nameToSlug(name);
        if (slug.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not generate a valid slug from the provided name");
        }

        // Ensure slug uniqueness
        if (businessRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            // Append a short random suffix to make it unique
            slug = slug + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        }

        Business business = new Business();
        business.setName(name.trim());
        business.setSlug(slug);
        business.setCurrency("KES");
        business.setCountryCode("KE");
        business.setTimezone("Africa/Nairobi");
        business.setSubscriptionTier("starter");
        business.setSettings("{}");
        Business saved = businessRepository.save(business);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(saved.getId());

        // Create domain mapping for the host the visitor is on
        DomainMapping mapping = new DomainMapping();
        mapping.setBusinessId(saved.getId());
        mapping.setDomain(lookup);
        mapping.setPrimary(true);
        mapping.setActive(true);
        domainMappingRepository.save(mapping);

        // If a slug-domain suffix is configured and the lookup host is the
        // platform apex (not already a subdomain), also create the canonical
        // {slug}.{suffix} mapping so the tenant has its own branded subdomain.
        String suffix = (slugDomainSuffix != null) ? slugDomainSuffix.trim().toLowerCase(Locale.ROOT) : "";
        if (!suffix.isEmpty() && !suffix.isBlank()) {
            String subdomain = slug + "." + suffix;
            if (!subdomain.equals(lookup)
                    && domainMappingRepository.findByDomainAndActiveTrue(subdomain).isEmpty()) {
                DomainMapping canonical = new DomainMapping();
                canonical.setBusinessId(saved.getId());
                canonical.setDomain(subdomain);
                canonical.setPrimary(false);
                canonical.setActive(true);
                domainMappingRepository.save(canonical);
            }
        }

        return toResponse(saved);
    }

    /**
     * Converts a business display name into a URL-safe slug:
     * lowercase, whitespace→hyphen, strip non-alphanumeric (except hyphens).
     */
    private String nameToSlug(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return name.trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "-")
                .replaceAll("[^a-z0-9-]", "")
                .replaceAll("-{2,}", "-")
                .replaceAll("^-+|-+$", "");
    }

    private PublicHostResolveResponse toResponse(Business business) {
        StorefrontSettingsResponse storefront =
                storefrontSettingsService.readFromSettingsJson(business.getSettings());
        TenantConfigBundle config =
                storefrontSettingsService.readTenantConfig(business.getSettings(), business.getName());
        TenantStatus status = business.getTenantStatus() != null
                ? business.getTenantStatus()
                : TenantStatus.ACTIVE;
        return new PublicHostResolveResponse(
                business.getId(),
                business.getName(),
                business.getSlug(),
                status.name(),
                config.branding(),
                config.authConfig(),
                config.featureFlags(),
                storefront.enabled(),
                Instant.now()
        );
    }
}
