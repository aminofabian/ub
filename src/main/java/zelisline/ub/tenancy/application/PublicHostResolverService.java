package zelisline.ub.tenancy.application;

import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Maps a browser hostname to its tenant configuration using the same active
 * domain mapping that {@code DomainBusinessResolverFilter} consults for
 * authenticated traffic. Exposed over an unauthenticated endpoint so the
 * Next.js frontend can drive UI, auth flows, branding, and feature gates
 * from a single resolve call per (host x cache-window).
 */
@Service
@RequiredArgsConstructor
public class PublicHostResolverService {

    private final DomainMappingRepository domainMappingRepository;
    private final BusinessRepository businessRepository;
    private final StorefrontSettingsService storefrontSettingsService;

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
