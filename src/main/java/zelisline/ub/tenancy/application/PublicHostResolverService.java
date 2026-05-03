package zelisline.ub.tenancy.application;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.infrastructure.TenantHostParsing;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

/**
 * Maps a browser hostname to its tenant slug using the same active domain
 * mapping that {@code DomainBusinessResolverFilter} consults for authenticated
 * traffic. Exposed over an unauthenticated endpoint so the Next.js storefront
 * can power "any mapped host &rarr; /shop" without putting the slug in the URL.
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
        return new PublicHostResolveResponse(
                business.getSlug(),
                business.getId(),
                business.getName(),
                storefront.enabled()
        );
    }
}
