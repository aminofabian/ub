package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.identity.repository.UserRepository;

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
    private final BranchRepository branchRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final CatalogBootstrapService catalogBootstrapService;
    private final BusinessOnboardingSettingsService businessOnboardingSettingsService;
    private final BusinessMobileSettingsService businessMobileSettingsService;
    private final UserRepository userRepository;
    private final RegionDefaults regionDefaults;
    private final RegionCatalogAuditService regionCatalogAuditService;

    public PublicHostResolverService(
            DomainMappingRepository domainMappingRepository,
            BusinessRepository businessRepository,
            BranchRepository branchRepository,
            StorefrontSettingsService storefrontSettingsService,
            CatalogBootstrapService catalogBootstrapService,
            BusinessOnboardingSettingsService businessOnboardingSettingsService,
            BusinessMobileSettingsService businessMobileSettingsService,
            UserRepository userRepository,
            RegionDefaults regionDefaults,
            RegionCatalogAuditService regionCatalogAuditService) {
        this.domainMappingRepository = domainMappingRepository;
        this.businessRepository = businessRepository;
        this.branchRepository = branchRepository;
        this.storefrontSettingsService = storefrontSettingsService;
        this.catalogBootstrapService = catalogBootstrapService;
        this.businessOnboardingSettingsService = businessOnboardingSettingsService;
        this.businessMobileSettingsService = businessMobileSettingsService;
        this.userRepository = userRepository;
        this.regionDefaults = regionDefaults;
        this.regionCatalogAuditService = regionCatalogAuditService;
    }

    /**
     * Looks up a user's business by email so the frontend can redirect
     * visitors from the landing page to their correct tenant subdomain.
     */
    @Transactional(readOnly = true)
    public Optional<PublicHostResolveResponse> resolveByEmail(String email) {
        if (email == null || email.isBlank()) {
            return Optional.empty();
        }
        return userRepository.findFirstActiveByEmail(email.trim().toLowerCase())
                .map(user -> businessRepository.findByIdAndDeletedAtIsNull(user.getBusinessId()))
                .flatMap(opt -> opt)
                .map(this::toResponse);
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
     * Landing-page shop finder: visitors type a business name, slug, or shop
     * host and we resolve the tenant so they can open the till on their subdomain.
     */
    @Transactional(readOnly = true)
    public Optional<PublicHostResolveResponse> resolveByShopQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Optional.empty();
        }
        String trimmed = rawQuery.trim();

        String asHost = TenantHostParsing.hostnameOnly(trimmed);
        if (asHost != null && asHost.contains(".")) {
            Optional<PublicHostResolveResponse> byHost = resolveByHost(asHost);
            if (byHost.isPresent()) {
                return byHost;
            }
            String left = asHost.split("\\.", 2)[0];
            if (!left.isBlank()) {
                Optional<Business> byLeftSlug =
                        businessRepository.findBySlugAndDeletedAtIsNull(left.toLowerCase(Locale.ROOT));
                if (byLeftSlug.isPresent()) {
                    return byLeftSlug.map(this::toResponse);
                }
            }
        }

        Optional<Business> byExactName =
                businessRepository.findFirstByNameIgnoreCaseAndDeletedAtIsNull(trimmed);
        if (byExactName.isPresent()) {
            return byExactName.map(this::toResponse);
        }

        String slug = nameToSlug(trimmed);
        if (slug.isBlank()) {
            return Optional.empty();
        }

        Optional<Business> bySlug = businessRepository.findBySlugAndDeletedAtIsNull(slug);
        if (bySlug.isPresent()) {
            return bySlug.map(this::toResponse);
        }

        List<Business> prefixed =
                businessRepository.findBySlugStartingWithAndDeletedAtIsNull(slug + "-");
        if (prefixed.size() == 1) {
            return Optional.of(toResponse(prefixed.get(0)));
        }

        return Optional.empty();
    }

    /**
     * Self-service onboarding: creates a business and a {@code {slug}.{suffix}}
     * subdomain mapping. The apex host (e.g. kiosk.ke) is NEVER mapped —
     * it stays as the landing page where visitors can only create shops.
     *
     * @param name        display name for the new business
     * @param rawHost     the host the visitor is currently on (used only for
     *                    logging/idempotency, not for domain mapping)
     * @param countryCode optional ISO country; omit/blank → Kenya; must be self-serve enabled
     * @return full resolve response for the newly created tenant
     */
    @Transactional
    public PublicHostResolveResponse onboardBusiness(String name, String rawHost, String countryCode) {
        String lookup = TenantHostParsing.hostnameOnly(rawHost);
        if (lookup == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "A valid host is required");
        }

        RegionProfile region = regionDefaults.requireSelfServe(countryCode);

        String slug = nameToSlug(name);
        if (slug.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Could not generate a valid slug from the provided name");
        }

        // Ensure slug uniqueness
        if (businessRepository.existsBySlugAndDeletedAtIsNull(slug)) {
            slug = slug + "-" + java.util.UUID.randomUUID().toString().substring(0, 6);
        }

        // Ensure the subdomain isn't already taken
        String suffix = (slugDomainSuffix != null) ? slugDomainSuffix.trim().toLowerCase(Locale.ROOT) : "";
        if (!suffix.isEmpty() && !suffix.isBlank()) {
            String subdomain = slug + "." + suffix;
            if (domainMappingRepository.findByDomainAndActiveTrue(subdomain).isPresent()) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "A business is already registered for " + subdomain);
            }
        }

        Business business = new Business();
        business.setName(name.trim());
        business.setSlug(slug);
        business.setCurrency(region.currency());
        business.setCountryCode(region.countryCode());
        business.setTimezone(region.timezone());
        business.setSubscriptionTier("starter");
        business.setSettings("{}");
        Business saved = businessRepository.save(business);
        saved.setSettings(
                businessOnboardingSettingsService.mergeInitialPending(saved.getSettings())
        );
        saved.setSettings(
                businessMobileSettingsService.mergeInitialProvision(
                        saved.getSettings(),
                        slug,
                        saved.getName()
                )
        );
        saved = businessRepository.save(saved);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(saved.getId());

        regionCatalogAuditService.countrySelected(
                saved.getId(),
                region.countryCode(),
                RegionCatalogAuditService.SOURCE_LANDING
        );

        // ONLY create the {slug}.{suffix} subdomain mapping —
        // the apex (kiosk.ke) stays as a landing page forever.
        if (!suffix.isEmpty() && !suffix.isBlank()) {
            String subdomain = slug + "." + suffix;
            DomainMapping mapping = new DomainMapping();
            mapping.setBusinessId(saved.getId());
            mapping.setDomain(subdomain);
            mapping.setPrimary(true);
            mapping.setActive(true);
            domainMappingRepository.save(mapping);
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
        OnboardingSettingsResponse onboarding =
                businessOnboardingSettingsService.readFromSettingsJson(business.getSettings());
        List<Branch> branches =
                branchRepository.findByBusinessIdAndDeletedAtIsNullOrderByNameAsc(business.getId());
        List<String> localities =
                BranchLocalityLabels.fromOnboardingAndBranches(onboarding, branches);
        String countryCode = business.getCountryCode() != null && !business.getCountryCode().isBlank()
                ? business.getCountryCode().trim().toUpperCase(Locale.ROOT)
                : null;
        return new PublicHostResolveResponse(
                business.getId(),
                business.getName(),
                business.getSlug(),
                status.name(),
                config.branding(),
                config.authConfig(),
                config.featureFlags(),
                storefront.enabled(),
                Instant.now(),
                countryCode,
                localities.isEmpty() ? List.of() : localities
        );
    }
}
