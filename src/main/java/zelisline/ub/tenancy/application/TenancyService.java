package zelisline.ub.tenancy.application;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.tenancy.api.dto.BranchResponse;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.api.dto.CreateBranchRequest;
import zelisline.ub.tenancy.api.dto.CreateBusinessRequest;
import zelisline.ub.tenancy.api.dto.DomainResponse;
import zelisline.ub.tenancy.api.dto.PatchBranchRequest;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.UpdateBusinessRequest;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@Service
@RequiredArgsConstructor
public class TenancyService {

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final BranchRepository branchRepository;
    private final CatalogBootstrapService catalogBootstrapService;
    private final StorefrontSettingsService storefrontSettingsService;

    @Transactional
    public BusinessResponse createBusiness(CreateBusinessRequest request) {
        String normalizedSlug = normalizeSlug(request.slug());
        if (businessRepository.existsBySlugAndDeletedAtIsNull(normalizedSlug)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Business slug already exists");
        }

        Business business = new Business();
        business.setName(request.name().trim());
        business.setSlug(normalizedSlug);
        business.setCurrency(normalizeCode(request.currency(), "KES"));
        business.setCountryCode(normalizeCode(request.countryCode(), "KE"));
        business.setTimezone(fallback(request.timezone(), "Africa/Nairobi"));
        business.setSubscriptionTier(fallback(request.subscriptionTier(), "starter").toLowerCase(Locale.ROOT));
        business.setSettings("{}");
        Business saved = businessRepository.save(business);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(saved.getId());

        String hostname = resolvePrimaryHostname(request.primaryDomain(), normalizedSlug);
        if (hostname != null) {
            DomainMapping domain = new DomainMapping();
            domain.setBusinessId(saved.getId());
            domain.setDomain(hostname);
            domain.setPrimary(true);
            domain.setActive(true);
            domainMappingRepository.save(domain);
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<BusinessResponse> listBusinesses(Pageable pageable) {
        return businessRepository.findByDeletedAtIsNull(pageable).map(this::toResponse);
    }

    @Transactional
    public BusinessResponse updateBusiness(String businessId, UpdateBusinessRequest request) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        if (request.name() != null && !request.name().isBlank()) {
            business.setName(request.name().trim());
        }
        if (request.subscriptionTier() != null && !request.subscriptionTier().isBlank()) {
            business.setSubscriptionTier(request.subscriptionTier().trim().toLowerCase(Locale.ROOT));
        }
        if (request.active() != null) {
            business.setActive(request.active());
        }
        if (request.storefront() != null) {
            String merged = storefrontSettingsService.mergeAndValidate(
                    business.getId(),
                    business.getSettings(),
                    request.storefront());
            business.setSettings(merged);
        }

        return toResponse(businessRepository.save(business));
    }

    @Transactional(readOnly = true)
    public List<DomainResponse> listDomains(String businessId) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
        return domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public DomainResponse addDomain(String businessId, String domainName) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }

        DomainMapping domain = new DomainMapping();
        domain.setBusinessId(businessId);
        domain.setDomain(domainName);
        domain.setActive(true);

        boolean hasExistingPrimary = !domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId).isEmpty();
        domain.setPrimary(!hasExistingPrimary);

        DomainMapping saved = domainMappingRepository.save(domain);
        return toResponse(saved);
    }

    @Transactional
    public DomainResponse setPrimaryDomain(String businessId, String domainId) {
        DomainMapping toPromote = domainMappingRepository.findById(domainId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found"));
        if (!toPromote.getBusinessId().equals(businessId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Domain not found");
        }

        List<DomainMapping> domains = domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        for (DomainMapping domain : domains) {
            domain.setPrimary(domain.getId().equals(toPromote.getId()));
        }
        domainMappingRepository.saveAll(domains);
        return toResponse(toPromote);
    }

    @Transactional(readOnly = true)
    public BusinessResponse getBusinessForTenant(String tenantBusinessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(tenantBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        return toResponse(business);
    }

    @Transactional
    public BusinessResponse updateBusinessForTenant(String tenantBusinessId, UpdateBusinessRequest request) {
        return updateBusiness(tenantBusinessId, request);
    }

    @Transactional(readOnly = true)
    public Page<BranchResponse> listBranches(String businessId, Pageable pageable) {
        requireBusiness(businessId);
        return branchRepository.findByBusinessIdAndDeletedAtIsNull(businessId, pageable)
                .map(this::toBranchResponse);
    }

    @Transactional
    public BranchResponse createBranch(String businessId, CreateBranchRequest request) {
        requireBusiness(businessId);
        String name = request.name().trim();
        if (branchRepository.existsByBusinessIdAndNameAndDeletedAtIsNull(businessId, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch name already exists for this business");
        }
        Branch branch = new Branch();
        branch.setBusinessId(businessId);
        branch.setName(name);
        branch.setAddress(blankToNull(request.address()));
        branch.setActive(true);
        return toBranchResponse(branchRepository.save(branch));
    }

    @Transactional
    public BranchResponse patchBranch(String businessId, String branchId, PatchBranchRequest request) {
        Branch branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        if (request.name() != null && !request.name().isBlank()) {
            String nextName = request.name().trim();
            if (!nextName.equals(branch.getName())
                    && branchRepository.existsByBusinessIdAndNameAndDeletedAtIsNull(businessId, nextName)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Branch name already exists for this business");
            }
            branch.setName(nextName);
        }
        if (request.address() != null) {
            branch.setAddress(blankToNull(request.address()));
        }
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        return toBranchResponse(branchRepository.save(branch));
    }

    private void requireBusiness(String businessId) {
        if (businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private BranchResponse toBranchResponse(Branch branch) {
        return new BranchResponse(
                branch.getId(),
                branch.getBusinessId(),
                branch.getName(),
                branch.getAddress(),
                branch.isActive(),
                branch.getCreatedAt(),
                branch.getUpdatedAt()
        );
    }

    private BusinessResponse toResponse(Business business) {
        StorefrontSettingsResponse storefront =
                storefrontSettingsService.readFromSettingsJson(business.getSettings());
        return new BusinessResponse(
                business.getId(),
                business.getName(),
                business.getSlug(),
                business.getCurrency(),
                business.getCountryCode(),
                business.getTimezone(),
                business.isActive(),
                business.getSubscriptionTier(),
                business.getCreatedAt(),
                business.getUpdatedAt(),
                storefront
        );
    }

    private DomainResponse toResponse(DomainMapping domain) {
        return new DomainResponse(
                domain.getId(),
                domain.getBusinessId(),
                domain.getDomain(),
                domain.isPrimary(),
                domain.isActive()
        );
    }

    private String resolvePrimaryHostname(String explicitPrimary, String normalizedSlug) {
        String trimmed = blankToNull(explicitPrimary);
        if (trimmed != null) {
            return trimmed;
        }
        String parent = blankToNull(slugDomainSuffix);
        if (parent == null) {
            return null;
        }
        parent = parent.trim().toLowerCase(Locale.ROOT);
        if (parent.isBlank()) {
            return null;
        }
        return normalizedSlug + "." + parent;
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Slug is required");
        }
        return slug.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeCode(String value, String fallback) {
        String source = fallback(value, fallback);
        return source.toUpperCase(Locale.ROOT);
    }

    private String fallback(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }
}
