package zelisline.ub.tenancy.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.PublicShopsSearchResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Public shop directory search for the apex "one door" sheet (Phase 4, §13).
 *
 * <p>Searches shop names, slugs, and hosts the same way the existing
 * {@code resolve-by-shop} finder does, but returns an ordered, capped list so
 * the apex can show a picker instead of guessing a single match. Names, slugs,
 * and branding are public (see §12); the returned {@code primaryHost} is the
 * tenant's own host so forward URLs are never built from raw user input.
 */
@Service
@RequiredArgsConstructor
public class PublicShopsSearchService {

    static final int MAX_RESULTS = 8;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final StorefrontSettingsService storefrontSettingsService;

    /**
     * Returns up to {@link #MAX_RESULTS} active shops matching the query.
     * Blank or too-short queries return an empty list (the caller's form
     * enforces minimum length).
     */
    @Transactional(readOnly = true)
    public List<PublicShopsSearchResponse> search(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return List.of();
        }
        String trimmed = rawQuery.trim();
        if (trimmed.length() < 2) {
            return List.of();
        }

        LinkedHashMap<String, PublicShopsSearchResponse> matches = new LinkedHashMap<>();
        String slug = nameToSlug(trimmed);

        // Pasted host: "{slug}.{suffix}" or a custom domain.
        String asHost = TenantHostParsing.hostnameOnly(trimmed);
        if (asHost != null && asHost.contains(".")) {
            resolveByHost(asHost).ifPresent(row -> matches.put(row.slug(), row));
            String left = asHost.split("\\.", 2)[0];
            if (!left.isBlank()) {
                businessRepository.findBySlugAndDeletedAtIsNull(left.toLowerCase(Locale.ROOT))
                        .ifPresent(business -> matches.putIfAbsent(business.getSlug(), toSearch(business)));
            }
        }

        // Exact slug, then exact name — the two highest-confidence matches.
        if (!slug.isBlank()) {
            businessRepository.findBySlugAndDeletedAtIsNull(slug)
                    .ifPresent(business -> matches.putIfAbsent(business.getSlug(), toSearch(business)));
        }
        businessRepository.findFirstByNameIgnoreCaseAndDeletedAtIsNull(trimmed)
                .ifPresent(business -> matches.putIfAbsent(business.getSlug(), toSearch(business)));

        // Slug prefix, then name contains — the fuzzy part, both capped.
        if (!slug.isBlank()) {
            for (Business business
                    : businessRepository.findTop8ByDeletedAtIsNullAndSlugStartingWithOrderBySlugAsc(slug)) {
                addIfRoom(matches, business);
            }
        }
        for (Business business
                : businessRepository.findTop8ByDeletedAtIsNullAndNameContainingIgnoreCaseOrderByNameAsc(trimmed)) {
            addIfRoom(matches, business);
        }

        return List.copyOf(matches.values());
    }

    private void addIfRoom(
            LinkedHashMap<String, PublicShopsSearchResponse> matches,
            Business business
    ) {
        if (matches.size() >= MAX_RESULTS) {
            return;
        }
        matches.putIfAbsent(business.getSlug(), toSearch(business));
    }

    private Optional<PublicShopsSearchResponse> resolveByHost(String host) {
        return domainMappingRepository.findByDomainAndActiveTrue(host)
                .map(DomainMapping::getBusinessId)
                .flatMap(businessRepository::findByIdAndDeletedAtIsNull)
                .map(this::toSearch);
    }

    private PublicShopsSearchResponse toSearch(Business business) {
        String logoUrl = null;
        try {
            logoUrl = storefrontSettingsService
                    .readTenantConfig(business.getSettings(), business.getName())
                    .branding()
                    .logoUrl();
        } catch (RuntimeException ignored) {
            // Branding is best-effort for a directory row; the row still ships.
        }
        return new PublicShopsSearchResponse(
                business.getSlug(),
                business.getName(),
                (logoUrl == null || logoUrl.isBlank()) ? null : logoUrl.trim(),
                primaryHostOf(business));
    }

    /**
     * The tenant's own primary host: the active primary {@code DomainMapping}
     * first, then the platform subdomain {@code {slug}.{suffix}} when a suffix
     * is configured. {@code null} only when neither exists — the frontend then
     * falls back to its own slug-derived origin.
     */
    private String primaryHostOf(Business business) {
        for (DomainMapping mapping
                : domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(business.getId())) {
            if (mapping.isPrimary() && mapping.isActive() && mapping.getDomain() != null) {
                return mapping.getDomain().trim().toLowerCase(Locale.ROOT);
            }
        }
        String suffix = slugDomainSuffix == null
                ? ""
                : slugDomainSuffix.trim().toLowerCase(Locale.ROOT);
        if (!suffix.isEmpty() && business.getSlug() != null && !business.getSlug().isBlank()) {
            return business.getSlug().toLowerCase(Locale.ROOT) + "." + suffix;
        }
        return null;
    }

    /** Lowercase, whitespace→hyphen, strip non-alphanumeric (kept in sync with the resolver). */
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
}
