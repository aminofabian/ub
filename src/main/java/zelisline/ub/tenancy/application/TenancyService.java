package zelisline.ub.tenancy.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.catalog.application.CatalogBootstrapService;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.finance.application.LedgerBootstrapService;
import zelisline.ub.globalcatalog.application.GlobalCatalogResolver;
import zelisline.ub.identity.domain.Role;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.domain.UserStatus;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.identity.repository.UserSessionRepository;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.sales.repository.ShiftRepository;
import zelisline.ub.tenancy.api.dto.BranchResponse;
import zelisline.ub.tenancy.api.dto.BrandingPatchRequest;
import zelisline.ub.tenancy.api.dto.BusinessResponse;
import zelisline.ub.tenancy.api.dto.CreateBranchRequest;
import zelisline.ub.tenancy.api.dto.CreateBusinessRequest;
import zelisline.ub.tenancy.api.dto.DomainResponse;
import zelisline.ub.tenancy.api.dto.InventorySettingsResponse;
import zelisline.ub.tenancy.api.dto.OnboardingPatchRequest;
import zelisline.ub.tenancy.api.dto.OnboardingSettingsResponse;
import zelisline.ub.tenancy.api.dto.PatchBranchRequest;
import zelisline.ub.tenancy.api.dto.ProfileSettingsResponse;
import zelisline.ub.tenancy.api.dto.SaBusinessStatsResponse;
import zelisline.ub.tenancy.api.dto.SaBusinessUserResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.UpdateBusinessRequest;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.DomainSource;
import zelisline.ub.tenancy.domain.DomainStatus;
import zelisline.ub.tenancy.domain.DomainZoneSource;
import zelisline.ub.tenancy.integrations.vercel.VercelProjectDomainClient;
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
    private final LedgerBootstrapService ledgerBootstrapService;
    private final StorefrontSettingsService storefrontSettingsService;
    private final BusinessInventorySettingsService businessInventorySettingsService;
    private final BusinessHubAlertsSettingsService businessHubAlertsSettingsService;
    private final BusinessProfileSettingsService businessProfileSettingsService;
    private final BusinessOnboardingSettingsService businessOnboardingSettingsService;
    private final BusinessMobileSettingsService businessMobileSettingsService;
    private final BranchReceiptSettingsService branchReceiptSettingsService;
    private final MediaStore cloudinaryImageService;
    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final RoleRepository roleRepository;
    private final ItemRepository itemRepository;
    private final ShiftRepository shiftRepository;
    private final SaleRepository saleRepository;
    private final zelisline.ub.storefront.repository.WebOrderRepository webOrderRepository;
    private final zelisline.ub.payments.repository.PaymentGatewayConfigRepository paymentGatewayConfigRepository;
    private final zelisline.ub.payments.repository.KioskPayAccountRepository kioskPayAccountRepository;
    private final GlobalCatalogResolver globalCatalogResolver;
    private final RegionDefaults regionDefaults;
    private final RegionCatalogAuditService regionCatalogAuditService;
    private final ReservedHostnameGuard reservedHostnameGuard;
    private final VercelProjectDomainClient vercelProjectDomainClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public BusinessResponse createBusiness(CreateBusinessRequest request) {
        String normalizedSlug = normalizeSlug(request.slug());
        if (businessRepository.existsBySlugAndDeletedAtIsNull(normalizedSlug)) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Business slug already exists"
            );
        }

        Business business = new Business();
        business.setName(request.name().trim());
        business.setSlug(normalizedSlug);
        business.setCurrency(normalizeCode(request.currency(), "KES"));
        business.setCountryCode(normalizeCode(request.countryCode(), "KE"));
        business.setTimezone(fallback(request.timezone(), "Africa/Nairobi"));
        business.setSubscriptionTier(
            fallback(request.subscriptionTier(), "starter").toLowerCase(
                Locale.ROOT
            )
        );
        business.setSettings("{}");
        Business saved = businessRepository.save(business);
        saved.setSettings(
                businessOnboardingSettingsService.mergeInitialPending(saved.getSettings())
        );
        saved.setSettings(
                businessMobileSettingsService.mergeInitialProvision(
                        saved.getSettings(),
                        normalizedSlug,
                        saved.getName()
                )
        );
        saved = businessRepository.save(saved);
        catalogBootstrapService.seedDefaultItemTypesIfMissing(saved.getId());
        ledgerBootstrapService.ensureStandardAccounts(saved.getId());

        String hostname = resolvePrimaryHostname(
            request.primaryDomain(),
            normalizedSlug
        );
        if (hostname != null) {
            DomainMapping domain = newPlatformSubdomain(saved.getId(), hostname);
            domainMappingRepository.save(domain);
        }

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<BusinessResponse> listBusinesses(Pageable pageable) {
        return businessRepository
            .findByDeletedAtIsNull(pageable)
            .map(this::toResponse);
    }

    @Transactional
    public BusinessResponse updateBusiness(
        String businessId,
        UpdateBusinessRequest request
    ) {
        return updateBusinessInternal(businessId, request, false);
    }

    @Transactional(readOnly = true)
    public OnboardingSettingsResponse getOnboardingForTenant(String tenantBusinessId) {
        Business business = requireTenantBusiness(tenantBusinessId);
        return businessOnboardingSettingsService.readFromSettingsJson(
                business.getSettings()
        );
    }

    @Transactional
    public OnboardingSettingsResponse updateOnboardingForTenant(
            String tenantBusinessId,
            OnboardingPatchRequest patch
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String settings = businessOnboardingSettingsService.merge(
                business.getSettings(),
                patch
        );
        if (patch.answers() != null) {
            if (patch.answers().storeTypes() != null && !patch.answers().storeTypes().isEmpty()) {
                settings = businessProfileSettingsService.mergeStoreTypes(
                        settings,
                        patch.answers().storeTypes()
                );
                regionCatalogAuditService.verticalSelected(
                        tenantBusinessId,
                        patch.answers().storeTypes()
                );
            } else if (patch.answers().storeType() != null) {
                settings = businessProfileSettingsService.mergeStoreType(
                        settings,
                        patch.answers().storeType()
                );
                regionCatalogAuditService.verticalSelected(
                        tenantBusinessId,
                        List.of(patch.answers().storeType())
                );
            }
        }
        business.setSettings(settings);
        Business saved = businessRepository.save(business);
        return businessOnboardingSettingsService.readFromSettingsJson(saved.getSettings());
    }

    @Transactional
    public BusinessResponse updateBrandingForTenant(
        String tenantBusinessId,
        BrandingPatchRequest patch
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        business.setSettings(
            storefrontSettingsService.mergeBranding(
                business.getSettings(),
                patch
            )
        );
        return toResponse(businessRepository.save(business));
    }

    @Transactional(readOnly = true)
    public List<DomainResponse> listDomains(String businessId) {
        if (
            businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()
        ) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Business not found"
            );
        }
        return domainMappingRepository
            .findByBusinessIdAndDeletedAtIsNull(businessId)
            .stream()
            .map(this::toResponse)
            .toList();
    }

    @Transactional
    public DomainResponse addDomain(String businessId, String domainName) {
        if (
            businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()
        ) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Business not found"
            );
        }
        String normalized = normalizeHostname(domainName);
        reservedHostnameGuard.assertClaimable(normalized);
        if (
            domainMappingRepository
                .findByDomainAndDeletedAtIsNull(normalized)
                .isPresent()
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Domain is already in use"
            );
        }

        DomainMapping domain = new DomainMapping();
        domain.setBusinessId(businessId);
        domain.setDomain(normalized);
        // Pending until Vercel verify (or ops activation). Host resolve ignores inactive.
        domain.setActive(false);
        domain.setStatus(DomainStatus.PENDING);
        domain.setSource(DomainSource.MANUAL_CONNECT);
        domain.setZoneSource(DomainZoneSource.EXTERNAL);

        boolean hasExistingDomain = !domainMappingRepository
            .findByBusinessIdAndDeletedAtIsNull(businessId)
            .isEmpty();
        // Keep platform subdomain as default primary when present.
        domain.setPrimary(!hasExistingDomain);

        applyVercelAttach(domain);
        DomainMapping saved = domainMappingRepository.save(domain);
        return toResponse(saved);
    }

    @Transactional
    public DomainResponse verifyDomain(String businessId, String domainId) {
        DomainMapping domain = requireOwnedDomain(businessId, domainId);
        if (domain.getSource() == DomainSource.PLATFORM_SUBDOMAIN) {
            return toResponse(domain);
        }
        domain.setStatus(DomainStatus.VERIFYING);
        domain.setLastError(null);

        if (!vercelProjectDomainClient.configured()) {
            domain.setStatus(DomainStatus.PENDING);
            domain.setLastError("vercel_not_configured");
            domainMappingRepository.save(domain);
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Domain verification requires Vercel configuration"
            );
        }

        var result = vercelProjectDomainClient.verifyDomain(domain.getDomain());
        if (result.skipped()) {
            domain.setStatus(DomainStatus.PENDING);
            domain.setLastError(result.error());
            domainMappingRepository.save(domain);
            throw new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "Domain verification unavailable"
            );
        }
        if (!result.ok()) {
            domain.setStatus(DomainStatus.FAILED);
            domain.setLastError(result.error());
            writeDnsInstructions(domain, result.dnsInstructions());
            domainMappingRepository.save(domain);
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Vercel verification failed: " + result.error()
            );
        }

        writeDnsInstructions(domain, result.dnsInstructions());
        if (result.verified()) {
            activateVerified(domain);
        } else {
            domain.setStatus(DomainStatus.PENDING);
            domain.setActive(false);
        }
        return toResponse(domainMappingRepository.save(domain));
    }

    @Transactional
    public void deleteDomain(String businessId, String domainId) {
        DomainMapping domain = requireOwnedDomain(businessId, domainId);
        if (domain.isPrimary()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Cannot delete the primary domain. Promote another domain first."
            );
        }
        if (domain.getSource() != DomainSource.PLATFORM_SUBDOMAIN
                && vercelProjectDomainClient.configured()) {
            vercelProjectDomainClient.removeDomain(domain.getDomain());
        }
        // Vacate UNIQUE(domain) so the hostname can be reclaimed.
        domain.setDomain(vacateHostname(domain.getDomain(), domain.getId()));
        domain.setActive(false);
        domain.setStatus(DomainStatus.FAILED);
        domain.setDeletedAt(Instant.now());
        domainMappingRepository.save(domain);
    }

    @Transactional
    public DomainResponse setPrimaryDomain(String businessId, String domainId) {
        DomainMapping toPromote = requireOwnedDomain(businessId, domainId);
        if (!toPromote.isActive() || toPromote.getStatus() != DomainStatus.ACTIVE) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Only verified active domains can be primary"
            );
        }

        Instant now = Instant.now();
        domainMappingRepository.clearPrimaryForBusinessExcept(
            businessId,
            toPromote.getId(),
            now
        );
        if (!toPromote.isPrimary()) {
            toPromote.setPrimary(true);
            toPromote = domainMappingRepository.save(toPromote);
        }
        return toResponse(toPromote);
    }

    @Transactional(readOnly = true)
    public BusinessResponse getBusinessForTenant(String tenantBusinessId) {
        Business business = businessRepository
            .findByIdAndDeletedAtIsNull(tenantBusinessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Business not found"
                )
            );
        return toResponse(business);
    }

    @Transactional
    public BusinessResponse updateBusinessForTenant(
        String tenantBusinessId,
        UpdateBusinessRequest request
    ) {
        return updateBusinessInternal(tenantBusinessId, request, true);
    }

    private BusinessResponse updateBusinessInternal(
        String businessId,
        UpdateBusinessRequest request,
        boolean tenantSelfServe
    ) {
        Business business = businessRepository
            .findByIdAndDeletedAtIsNull(businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Business not found"
                )
            );

        if (tenantSelfServe && hasRegionFields(request)) {
            assertTenantRegionEditable(business);
        }

        if (request.name() != null && !request.name().isBlank()) {
            business.setName(request.name().trim());
        }
        if (
            request.subscriptionTier() != null &&
            !request.subscriptionTier().isBlank()
        ) {
            business.setSubscriptionTier(
                request.subscriptionTier().trim().toLowerCase(Locale.ROOT)
            );
        }
        if (request.active() != null) {
            business.setActive(request.active());
        }
        if (request.storefront() != null) {
            String merged = storefrontSettingsService.mergeAndValidate(
                business.getId(),
                business.getSettings(),
                request.storefront()
            );
            business.setSettings(merged);
        }
        if (request.inventory() != null) {
            business.setSettings(
                businessInventorySettingsService.merge(
                    business.getSettings(),
                    request.inventory()
                )
            );
        }
        if (request.profile() != null) {
            business.setSettings(
                businessProfileSettingsService.merge(
                    business.getSettings(),
                    request.profile()
                )
            );
        }
        if (request.featureFlags() != null) {
            business.setSettings(
                storefrontSettingsService.mergeFeatureFlags(
                    business.getSettings(),
                    request.featureFlags()
                )
            );
        }
        if (request.hubAlerts() != null) {
            business.setSettings(
                businessHubAlertsSettingsService.merge(
                    business.getSettings(),
                    request.hubAlerts()
                )
            );
        }
        if (request.globalCatalogCode() != null) {
            business.setSettings(
                globalCatalogResolver.mergeOverrideCode(
                    business.getSettings(),
                    request.globalCatalogCode()
                )
            );
        }
        applyRegionPatch(business, request, tenantSelfServe);

        return toResponse(businessRepository.save(business));
    }

    @Transactional
    public BusinessResponse uploadBrandingLogo(
        String tenantBusinessId,
        byte[] fileBytes,
        String originalFilename
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingLogoPublicId(
                business.getSettings()
            );
        String folder = "ub/" + tenantBusinessId + "/branding/logo";
        CloudinaryUploadResult uploaded =
            cloudinaryImageService.uploadImageToFolder(
                fileBytes,
                originalFilename,
                folder,
                true
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingLogo(
                business.getSettings(),
                uploaded.secureUrl(),
                uploaded.publicId()
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (
            previousPublicId != null &&
            !previousPublicId.equals(uploaded.publicId())
        ) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse clearBrandingLogo(String tenantBusinessId) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingLogoPublicId(
                business.getSettings()
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingLogo(
                business.getSettings(),
                null,
                null
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (previousPublicId != null) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse uploadBrandingFavicon(
        String tenantBusinessId,
        byte[] fileBytes,
        String originalFilename
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingFaviconPublicId(
                business.getSettings()
            );
        String folder = "ub/" + tenantBusinessId + "/branding/favicon";
        CloudinaryUploadResult uploaded =
            cloudinaryImageService.uploadImageToFolder(
                fileBytes,
                originalFilename,
                folder,
                false
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingFavicon(
                business.getSettings(),
                uploaded.secureUrl(),
                uploaded.publicId()
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (
            previousPublicId != null &&
            !previousPublicId.equals(uploaded.publicId())
        ) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse clearBrandingFavicon(String tenantBusinessId) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingFaviconPublicId(
                business.getSettings()
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingFavicon(
                business.getSettings(),
                null,
                null
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (previousPublicId != null) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse uploadBrandingOgImage(
        String tenantBusinessId,
        byte[] fileBytes,
        String originalFilename
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingOgImagePublicId(
                business.getSettings()
            );
        String folder = "ub/" + tenantBusinessId + "/branding/og-image";
        CloudinaryUploadResult uploaded =
            cloudinaryImageService.uploadImageToFolder(
                fileBytes,
                originalFilename,
                folder,
                false
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingOgImage(
                business.getSettings(),
                uploaded.secureUrl(),
                uploaded.publicId()
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (
            previousPublicId != null &&
            !previousPublicId.equals(uploaded.publicId())
        ) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse clearBrandingOgImage(String tenantBusinessId) {
        Business business = requireTenantBusiness(tenantBusinessId);
        String previousPublicId =
            storefrontSettingsService.readBrandingOgImagePublicId(
                business.getSettings()
            );
        business.setSettings(
            storefrontSettingsService.mergeBrandingOgImage(
                business.getSettings(),
                null,
                null
            )
        );
        BusinessResponse out = toResponse(businessRepository.save(business));
        if (previousPublicId != null) {
            cloudinaryImageService.destroyImage(previousPublicId);
        }
        return out;
    }

    @Transactional
    public BusinessResponse addBrandingBanner(
        String tenantBusinessId,
        String url,
        String publicId
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        business.setSettings(
            storefrontSettingsService.mergeBrandingBannerAdd(
                business.getSettings(),
                url,
                publicId
            )
        );
        return toResponse(businessRepository.save(business));
    }

    @Transactional
    public BusinessResponse deleteBrandingBanner(
        String tenantBusinessId,
        int index
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        List<String> publicIds =
            storefrontSettingsService.readBrandingBannerPublicIds(
                business.getSettings()
            );
        if (index >= 0 && index < publicIds.size()) {
            String publicId = publicIds.get(index);
            if (publicId != null && !publicId.isBlank()) {
                cloudinaryImageService.destroyImage(publicId);
            }
        }
        business.setSettings(
            storefrontSettingsService.mergeBrandingBannerRemove(
                business.getSettings(),
                index
            )
        );
        return toResponse(businessRepository.save(business));
    }

    @Transactional
    public BusinessResponse reorderBrandingBanners(
        String tenantBusinessId,
        List<String> orderedUrls
    ) {
        Business business = requireTenantBusiness(tenantBusinessId);
        business.setSettings(
            storefrontSettingsService.mergeBrandingBannersReorder(
                business.getSettings(),
                orderedUrls
            )
        );
        return toResponse(businessRepository.save(business));
    }

    private Business requireTenantBusiness(String tenantBusinessId) {
        return businessRepository
            .findByIdAndDeletedAtIsNull(tenantBusinessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Business not found"
                )
            );
    }

    @Transactional(readOnly = true)
    public Page<BranchResponse> listBranches(
        String businessId,
        Pageable pageable
    ) {
        requireBusiness(businessId);
        return branchRepository
            .findByBusinessIdAndDeletedAtIsNull(businessId, pageable)
            .map(this::toBranchResponse);
    }

    @Transactional
    public BranchResponse createBranch(
        String businessId,
        CreateBranchRequest request
    ) {
        requireBusiness(businessId);
        String name = request.name().trim();
        if (
            branchRepository.existsByBusinessIdAndNameAndDeletedAtIsNull(
                businessId,
                name
            )
        ) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Branch name already exists for this business"
            );
        }
        Branch branch = new Branch();
        branch.setBusinessId(businessId);
        branch.setName(name);
        branch.setAddress(blankToNull(request.address()));
        if (request.receipt() != null) {
            branch.setReceiptSettings(
                    branchReceiptSettingsService.merge(null, request.receipt())
            );
        }
        branch.setActive(true);
        return toBranchResponse(branchRepository.save(branch));
    }

    @Transactional
    public BranchResponse patchBranch(
        String businessId,
        String branchId,
        PatchBranchRequest request
    ) {
        Branch branch = branchRepository
            .findByIdAndBusinessIdAndDeletedAtIsNull(branchId, businessId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Branch not found"
                )
            );
        if (request.name() != null && !request.name().isBlank()) {
            String nextName = request.name().trim();
            if (
                !nextName.equals(branch.getName()) &&
                branchRepository.existsByBusinessIdAndNameAndDeletedAtIsNull(
                    businessId,
                    nextName
                )
            ) {
                throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Branch name already exists for this business"
                );
            }
            branch.setName(nextName);
        }
        if (request.address() != null) {
            branch.setAddress(blankToNull(request.address()));
        }
        if (request.active() != null) {
            branch.setActive(request.active());
        }
        if (request.receipt() != null) {
            branch.setReceiptSettings(
                    branchReceiptSettingsService.merge(branch.getReceiptSettings(), request.receipt())
            );
        }
        return toBranchResponse(branchRepository.save(branch));
    }

    private void requireBusiness(String businessId) {
        if (
            businessRepository.findByIdAndDeletedAtIsNull(businessId).isEmpty()
        ) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Business not found"
            );
        }
    }

    /** Super-admin / platform lookup by id (includes slug + primary domain). */
    @Transactional(readOnly = true)
    public BusinessResponse getBusiness(String businessId) {
        return businessRepository
            .findByIdAndDeletedAtIsNull(businessId)
            .map(this::toResponse)
            .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found")
            );
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
            branchReceiptSettingsService.read(branch.getReceiptSettings()),
            branch.isActive(),
            branch.getCreatedAt(),
            branch.getUpdatedAt()
        );
    }

    private BusinessResponse toResponse(Business business) {
        StorefrontSettingsResponse storefront =
            storefrontSettingsService.readFromSettingsJson(
                business.getSettings()
            );
        InventorySettingsResponse inventory =
            businessInventorySettingsService.readFromSettingsJson(
                business.getSettings()
            );
        var hubAlerts =
            businessHubAlertsSettingsService.readFromSettingsJson(
                business.getSettings()
            );
        ProfileSettingsResponse profile =
            businessProfileSettingsService.readFromSettingsJson(
                business.getSettings()
            );
        OnboardingSettingsResponse onboarding =
            businessOnboardingSettingsService.readFromSettingsJson(
                business.getSettings()
            );
        var bundle = storefrontSettingsService.readTenantConfig(
            business.getSettings(),
            business.getName()
        );
        String primaryDomain = domainMappingRepository
            .findByBusinessIdAndDeletedAtIsNull(business.getId())
            .stream()
            .filter(d -> d.isPrimary() && d.isActive())
            .map(DomainMapping::getDomain)
            .findFirst()
            .orElse(null);
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
            storefront,
            inventory,
            profile,
            onboarding,
            bundle.branding(),
            bundle.featureFlags(),
            hubAlerts,
            primaryDomain,
            globalCatalogResolver.readOverrideCode(business.getSettings())
        );
    }

    private DomainResponse toResponse(DomainMapping domain) {
        return new DomainResponse(
            domain.getId(),
            domain.getBusinessId(),
            domain.getDomain(),
            domain.isPrimary(),
            domain.isActive(),
            domain.getStatus() == null ? null : domain.getStatus().name().toLowerCase(Locale.ROOT),
            domain.getSource() == null ? null : domain.getSource().name().toLowerCase(Locale.ROOT),
            domain.getZoneSource() == null ? null : domain.getZoneSource().name().toLowerCase(Locale.ROOT),
            domain.getVerifiedAt(),
            readDnsInstructions(domain),
            domain.getLastError()
        );
    }

    private DomainMapping newPlatformSubdomain(String businessId, String hostname) {
        DomainMapping domain = new DomainMapping();
        domain.setBusinessId(businessId);
        domain.setDomain(hostname);
        domain.setPrimary(true);
        domain.setActive(true);
        domain.setStatus(DomainStatus.ACTIVE);
        domain.setSource(DomainSource.PLATFORM_SUBDOMAIN);
        domain.setZoneSource(null);
        domain.setVerifiedAt(Instant.now());
        return domain;
    }

    private DomainMapping requireOwnedDomain(String businessId, String domainId) {
        DomainMapping domain = domainMappingRepository
            .findById(domainId)
            .orElseThrow(() ->
                new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Domain not found"
                )
            );
        if (
            !domain.getBusinessId().equals(businessId) ||
            domain.getDeletedAt() != null
        ) {
            throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Domain not found"
            );
        }
        return domain;
    }

    private void applyVercelAttach(DomainMapping domain) {
        Map<String, Object> fallback = defaultExternalDnsInstructions(domain.getDomain());
        if (!vercelProjectDomainClient.configured()) {
            writeDnsInstructions(domain, fallback);
            return;
        }
        var result = vercelProjectDomainClient.addDomain(domain.getDomain());
        if (result.skipped()) {
            writeDnsInstructions(domain, fallback);
            return;
        }
        if (!result.ok()) {
            domain.setStatus(DomainStatus.FAILED);
            domain.setLastError(result.error());
            writeDnsInstructions(domain, fallback);
            return;
        }
        writeDnsInstructions(
            domain,
            result.dnsInstructions() == null || result.dnsInstructions().isEmpty()
                ? fallback
                : result.dnsInstructions()
        );
        if (result.verified()) {
            activateVerified(domain);
        }
    }

    private void activateVerified(DomainMapping domain) {
        domain.setActive(true);
        domain.setStatus(DomainStatus.ACTIVE);
        domain.setVerifiedAt(Instant.now());
        domain.setLastError(null);
    }

    private void writeDnsInstructions(DomainMapping domain, Map<String, Object> instructions) {
        if (instructions == null || instructions.isEmpty()) {
            domain.setDnsInstructionJson(null);
            return;
        }
        try {
            domain.setDnsInstructionJson(objectMapper.writeValueAsString(instructions));
        } catch (Exception ex) {
            domain.setDnsInstructionJson(null);
        }
    }

    private Map<String, Object> readDnsInstructions(DomainMapping domain) {
        String raw = domain.getDnsInstructionJson();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return null;
        }
    }

    private static Map<String, Object> defaultExternalDnsInstructions(String hostname) {
        Map<String, Object> instructions = new LinkedHashMap<>();
        instructions.put("provider", "external");
        instructions.put("hostname", hostname);
        instructions.put(
            "recommendedRecords",
            List.of(
                Map.of("type", "CNAME", "name", "www", "value", "cname.vercel-dns.com"),
                Map.of("type", "A", "name", "@", "value", "76.76.21.21")
            )
        );
        instructions.put(
            "note",
            "Point DNS at Vercel, then click Verify. Apex may use A 76.76.21.21; www should CNAME to cname.vercel-dns.com."
        );
        return instructions;
    }

    /** Soft-delete vacate so UNIQUE(domain) can be reclaimed (mirrors archived business slugs). */
    static String vacateHostname(String hostname, String id) {
        String base = hostname == null ? "domain" : hostname.trim().toLowerCase(Locale.ROOT);
        String suffix = "-" + id;
        int max = 255;
        if (base.length() + suffix.length() <= max) {
            return base + suffix;
        }
        int keep = Math.max(1, max - suffix.length());
        return base.substring(0, keep) + suffix;
    }

    // ── Super-admin: business users ────────────────────────────────────

    @Transactional(readOnly = true)
    public List<SaBusinessUserResponse> getBusinessUsers(String businessId) {
        requireBusiness(businessId);

        // Load all users for the business
        Page<User> userPage = userRepository.pageByBusiness(businessId, Pageable.unpaged());
        List<User> users = userPage.getContent();

        Map<String, Role> roleById = rolesById(businessId);
        Map<String, String> branchNameById = branchNameById(businessId);
        return users.stream()
                .map(u -> toSaBusinessUser(u, roleById, branchNameById))
                .toList();
    }

    /**
     * Super-admin override for a tenant user's lifecycle status
     * (invited → active → suspended → locked).
     *
     * <p>Moving an active user out of {@code active} revokes their sessions and
     * refuses to deactivate the last active owner, mirroring the tenant-side
     * deactivation guards in {@code IdentityService}.
     */
    @Transactional
    public SaBusinessUserResponse updateBusinessUserStatus(
            String businessId, String userId, String statusWire) {
        requireBusiness(businessId);
        User user = userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "User not found"));

        UserStatus current = user.statusAsEnum();
        UserStatus next = parseUserStatus(statusWire, current);
        if (next != current) {
            if (next != UserStatus.ACTIVE && current == UserStatus.ACTIVE) {
                if (hasOwnerRole(user.getRoleId())) {
                    guardLastActiveOwner(businessId);
                }
                userSessionRepository.revokeAllActiveForUser(user.getId(), Instant.now());
            }
            user.setStatus(next);
            user = userRepository.save(user);
        }

        return toSaBusinessUser(user, rolesById(businessId), branchNameById(businessId));
    }

    private SaBusinessUserResponse toSaBusinessUser(
            User user, Map<String, Role> roleById, Map<String, String> branchNameById) {
        Role role = roleById.get(user.getRoleId());
        return new SaBusinessUserResponse(
                user.getId(),
                user.getEmail(),
                user.getName(),
                user.getPhone(),
                user.getStatus(),
                role != null ? role.getRoleKey() : "unknown",
                role != null ? role.getName() : "Unknown",
                user.getBranchId() != null
                        ? branchNameById.getOrDefault(user.getBranchId(), "—")
                        : "—",
                user.getLastLoginAt(),
                user.getCreatedAt()
        );
    }

    private Map<String, Role> rolesById(String businessId) {
        return roleRepository.findVisibleForTenant(businessId).stream()
                .collect(Collectors.toMap(Role::getId, r -> r));
    }

    private Map<String, String> branchNameById(String businessId) {
        return branchRepository.findByBusinessIdAndDeletedAtIsNull(businessId, Pageable.unpaged())
                .getContent().stream()
                .collect(Collectors.toMap(Branch::getId, Branch::getName));
    }

    private UserStatus parseUserStatus(String wire, UserStatus fallback) {
        if (wire == null || wire.isBlank()) {
            return fallback;
        }
        try {
            return UserStatus.fromWire(wire);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown user status: " + wire);
        }
    }

    private boolean hasOwnerRole(String roleId) {
        return roleRepository.findById(roleId)
                .map(r -> "owner".equals(r.getRoleKey()))
                .orElse(false);
    }

    private void guardLastActiveOwner(String businessId) {
        long owners = userRepository.countActiveByRoleKey(businessId, "owner");
        if (owners <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Cannot deactivate the last active owner of this tenant");
        }
    }

    // ── Super-admin: business stats ─────────────────────────────────────

    @Transactional(readOnly = true)
    public SaBusinessStatsResponse getBusinessStats(String businessId) {
        requireBusiness(businessId);

        long totalUsers = userRepository.countByBusinessIdAndDeletedAtIsNull(businessId);
        long activeUsers = userRepository.pageByBusinessFiltered(
                businessId, "active", null, null, Pageable.unpaged()
        ).getTotalElements();

        long totalProducts = itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrue(businessId);
        long webPublished = itemRepository.countByBusinessIdAndDeletedAtIsNullAndActiveTrueAndWebPublishedTrue(businessId);

        long totalBranches = branchRepository.findByBusinessIdAndDeletedAtIsNull(businessId, Pageable.unpaged())
                .getTotalElements();

        long openShifts = shiftRepository.findByBusinessIdFiltered(
                businessId, null, "open", Pageable.unpaged()
        ).getTotalElements();

        Instant now = Instant.now();
        Instant startOfToday = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
                .atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        Instant since7d = now.minusSeconds(7L * 24 * 3600);
        Instant since30d = now.minusSeconds(30L * 24 * 3600);

        Object[] today = firstAgg(saleRepository.aggregateSalesBetween(businessId, startOfToday, now));
        Object[] week = firstAgg(saleRepository.aggregateSalesBetween(businessId, since7d, now));
        Object[] month = firstAgg(saleRepository.aggregateSalesBetween(businessId, since30d, now));
        Object[] all = firstAgg(saleRepository.aggregateSalesAllTime(businessId));

        BigDecimal unitsToday = nz(saleRepository.unitsSoldBetween(businessId, startOfToday, now));
        BigDecimal units30 = nz(saleRepository.unitsSoldBetween(businessId, since30d, now));
        BigDecimal unitsAll = nz(saleRepository.unitsSoldAllTime(businessId));

        Instant lastSaleAt = saleRepository.findLastSaleAt(businessId);
        Instant lastLoginAt = userRepository.findMaxLastLoginAt(businessId);

        Object[] sf30 = firstAgg(webOrderRepository.aggregatePaidBetween(businessId, since30d, now));
        Object[] sfAll = firstAgg(webOrderRepository.aggregatePaidAllTime(businessId));

        List<SaBusinessStatsResponse.PaymentMethodRow> methods = paymentGatewayConfigRepository
                .findByBusinessId(businessId)
                .stream()
                .map(cfg -> new SaBusinessStatsResponse.PaymentMethodRow(
                        cfg.getGatewayType() == null ? "" : cfg.getGatewayType().name(),
                        cfg.getLabel(),
                        cfg.getStatus() == null ? "" : cfg.getStatus().name(),
                        cfg.isDefault()
                ))
                .toList();

        var kiosk = kioskPayAccountRepository.findByBusinessId(businessId).orElse(null);
        boolean kioskActive = kiosk != null && kiosk.isActive();
        String kioskStatus = kiosk == null ? "OFF" : kiosk.getStatus();

        String onboarding = businessOnboardingSettingsService
                .readFromSettingsJson(businessRepository.findSettingsJsonById(businessId).orElse(null))
                .status();

        return new SaBusinessStatsResponse(
                totalUsers,
                activeUsers,
                totalProducts,
                webPublished,
                totalBranches,
                openShifts,
                new SaBusinessStatsResponse.SalesPulse(
                        toLong(today[0]),
                        toBd(today[1]),
                        unitsToday,
                        toLong(week[0]),
                        toBd(week[1]),
                        toLong(month[0]),
                        toBd(month[1]),
                        units30,
                        toLong(all[0]),
                        toBd(all[1]),
                        unitsAll
                ),
                new SaBusinessStatsResponse.StorefrontPulse(
                        toLong(sf30[0]),
                        toBd(sf30[1]),
                        toLong(sfAll[0]),
                        toBd(sfAll[1])
                ),
                methods,
                kioskActive,
                kioskStatus,
                onboarding,
                lastLoginAt == null ? null : lastLoginAt.toString(),
                lastSaleAt == null ? null : lastSaleAt.toString()
        );
    }

    private static Object[] firstAgg(List<Object[]> rows) {
        if (rows == null || rows.isEmpty() || rows.get(0) == null) {
            return new Object[] {0L, BigDecimal.ZERO};
        }
        Object first = rows.get(0);
        if (first instanceof Object[] arr) {
            return arr.length >= 2 ? arr : new Object[] {arr.length > 0 ? arr[0] : 0L, BigDecimal.ZERO};
        }
        return new Object[] {first, rows.size() > 1 ? rows.get(1) : BigDecimal.ZERO};
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal toBd(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal bd) {
            return bd;
        }
        if (raw instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private static long toLong(Object raw) {
        if (raw == null) {
            return 0L;
        }
        if (raw instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private String resolvePrimaryHostname(
        String explicitPrimary,
        String normalizedSlug
    ) {
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

    private String normalizeHostname(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Domain is required"
            );
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Slug is required"
            );
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

    private static boolean hasRegionFields(UpdateBusinessRequest request) {
        return request.currency() != null
                || request.countryCode() != null
                || request.timezone() != null;
    }

    private void assertTenantRegionEditable(Business business) {
        String status = businessOnboardingSettingsService
                .readFromSettingsJson(business.getSettings())
                .status();
        if ("completed".equals(status) || "dismissed".equals(status)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Country, currency, and timezone are locked after onboarding is finished"
            );
        }
    }

    /**
     * @param tenantSelfServe when true, currency must match the country map (no free-pick).
     *                        When false (SA), free-pick is allowed; country still must be known
     *                        if provided, and changing country alone re-derives currency/tz
     *                        unless those fields are also supplied.
     */
    private void applyRegionPatch(
            Business business,
            UpdateBusinessRequest request,
            boolean tenantSelfServe
    ) {
        if (!hasRegionFields(request)) {
            return;
        }

        String previousCountry = business.getCountryCode();
        String previousCurrency = business.getCurrency();
        String previousTimezone = business.getTimezone();

        String nextCountry = request.countryCode() != null
                ? RegionDefaults.normalizeCountry(request.countryCode())
                : business.getCountryCode();
        if (request.countryCode() != null) {
            RegionProfile profile = regionDefaults.require(nextCountry);
            business.setCountryCode(profile.countryCode());
            if (request.currency() == null) {
                business.setCurrency(profile.currency());
            }
            if (request.timezone() == null) {
                business.setTimezone(profile.timezone());
            }
        }

        if (request.currency() != null) {
            String currency = request.currency().trim().toUpperCase(Locale.ROOT);
            if (tenantSelfServe && !regionDefaults.currencyMatchesCountry(nextCountry, currency)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Currency must match the country default for self-serve tenants"
                );
            }
            business.setCurrency(currency);
        }

        if (request.timezone() != null) {
            String timezone = request.timezone().trim();
            if (timezone.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Timezone is required");
            }
            business.setTimezone(timezone);
        }

        boolean countryChanged = !java.util.Objects.equals(
                normalizeNullable(previousCountry),
                normalizeNullable(business.getCountryCode())
        );
        boolean currencyChanged = !java.util.Objects.equals(
                normalizeNullable(previousCurrency),
                normalizeNullable(business.getCurrency())
        );

        if (tenantSelfServe && countryChanged) {
            regionCatalogAuditService.countrySelected(
                    business.getId(),
                    business.getCountryCode(),
                    RegionCatalogAuditService.SOURCE_QUESTIONNAIRE
            );
        }

        if (!tenantSelfServe && (countryChanged || currencyChanged)) {
            boolean hasRisk = itemRepository.existsByBusinessIdAndDeletedAtIsNull(business.getId())
                    || saleRepository.existsByBusinessId(business.getId());
            if (hasRisk && !Boolean.TRUE.equals(request.acknowledgeRegionRisk())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Changing country/currency re-labels existing amounts without converting. "
                                + "This business has products and/or sales. "
                                + "Confirm with acknowledgeRegionRisk=true."
                );
            }
            regionCatalogAuditService.regionChangedBySuperAdmin(
                    business.getId(),
                    currentActorId(),
                    Map.of(
                            "countryCode", nullToEmpty(previousCountry),
                            "currency", nullToEmpty(previousCurrency),
                            "timezone", nullToEmpty(previousTimezone)
                    ),
                    Map.of(
                            "countryCode", nullToEmpty(business.getCountryCode()),
                            "currency", nullToEmpty(business.getCurrency()),
                            "timezone", nullToEmpty(business.getTimezone())
                    ),
                    hasRisk
            );
        }
    }

    private static String normalizeNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String currentActorId() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder
                .getContext()
                .getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String id) || id.isBlank()) {
            return null;
        }
        return id;
    }
}
