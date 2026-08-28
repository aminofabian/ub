package zelisline.ub.storefront.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class PublicStorefrontContextService {

    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final StorefrontSettingsService storefrontSettingsService;

    /**
     * Live shop: catalog branch is set and the storefront is switched on.
     * Cart, checkout, and the storefront payload stay behind this gate.
     */
    public PublicStorefrontContext requireForSlug(String slug) {
        return resolve(slug, true);
    }

    /**
     * Catalog teaser: published stock can dress the coming-soon landing even
     * while the public shop is still closed. Does not open checkout.
     */
    public PublicStorefrontContext requireCatalogForSlug(String slug) {
        return resolve(slug, false);
    }

    private PublicStorefrontContext resolve(String slug, boolean requireEnabled) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        StorefrontSettingsResponse sf = storefrontSettingsService.readFromSettingsJson(business.getSettings());
        if (sf.catalogBranchId() == null || sf.catalogBranchId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        if (requireEnabled && !sf.enabled()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        Branch branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(sf.catalogBranchId(), business.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!branch.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        return new PublicStorefrontContext(business, branch, sf);
    }
}
