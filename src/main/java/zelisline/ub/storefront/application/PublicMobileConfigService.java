package zelisline.ub.storefront.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.PublicMobileAppResponse;
import zelisline.ub.storefront.api.dto.PublicMobileConfigResponse;
import zelisline.ub.storefront.api.dto.PublicMobileDeepLinksResponse;
import zelisline.ub.storefront.api.dto.PublicMobileStoreLinksResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;
import zelisline.ub.tenancy.api.dto.MobileAppSettingsDto;
import zelisline.ub.tenancy.api.dto.MobileSettingsResponse;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.api.dto.TenantConfigBundle;
import zelisline.ub.tenancy.application.BusinessMobileSettingsService;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.DomainMapping;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.tenancy.repository.DomainMappingRepository;

@Service
@RequiredArgsConstructor
public class PublicMobileConfigService {

    private static final List<String> STAFF_ROLES = List.of("cashier", "grocery", "admin", "stock");

    private final BusinessRepository businessRepository;
    private final DomainMappingRepository domainMappingRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final BusinessMobileSettingsService businessMobileSettingsService;

    @Value("${app.mobile.api-base-url:https://api.kiosk.ke}")
    private String apiBaseUrl;

    @Value("${app.mobile.deep-link-scheme:kiosk}")
    private String deepLinkScheme;

    @Value("${app.mobile.universal-link-scheme:https}")
    private String universalLinkScheme;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    @Value("${app.mobile.store-links.ios:}")
    private String platformIosStoreUrl;

    @Value("${app.mobile.store-links.android:}")
    private String platformAndroidStoreUrl;

    @Value("${app.mobile.platform-bundles.shopper:com.kioskke.shopper}")
    private String platformShopperBundleId;

    @Value("${app.mobile.platform-bundles.cashier:com.kioskke.cashier}")
    private String platformCashierBundleId;

    @Value("${app.mobile.platform-bundles.grocery:com.kioskke.grocery}")
    private String platformGroceryBundleId;

    @Value("${app.mobile.platform-bundles.admin:com.kioskke.admin}")
    private String platformAdminBundleId;

    @Value("${app.mobile.platform-bundles.stock:com.kioskke.stock}")
    private String platformStockBundleId;

    @Transactional(readOnly = true)
    public PublicMobileConfigResponse getForSlug(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }

        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));

        TenantStatus status = business.getTenantStatus() != null
                ? business.getTenantStatus()
                : TenantStatus.ACTIVE;

        StorefrontSettingsResponse storefront = storefrontSettingsService.readFromSettingsJson(
                business.getSettings());
        TenantConfigBundle tenantConfig = storefrontSettingsService.readTenantConfig(
                business.getSettings(),
                business.getName());
        TenantBrandingDto branding = tenantConfig.branding();
        MobileSettingsResponse mobile = businessMobileSettingsService.readFromSettingsJson(
                business.getSettings());

        String normalizedSlug = business.getSlug();
        String tenantHost = resolveTenantHost(business);
        String displayName = branding.displayName() != null && !branding.displayName().isBlank()
                ? branding.displayName().trim()
                : business.getName();

        PublicMobileDeepLinksResponse deepLinks = buildDeepLinks(normalizedSlug, tenantHost);
        PublicMobileStoreLinksResponse platformStoreLinks = resolvePlatformStoreLinks(mobile);

        List<PublicMobileAppResponse> apps = new ArrayList<>();
        apps.add(buildApp("shopper", displayName, normalizedSlug, platformStoreLinks, mobile));
        for (String role : STAFF_ROLES) {
            String staffName = staffAppName(displayName, role);
            apps.add(buildApp(role, staffName, normalizedSlug, platformStoreLinks, mobile));
        }

        return new PublicMobileConfigResponse(
                business.getId(),
                normalizedSlug,
                displayName,
                tenantHost,
                status.name(),
                storefront.enabled(),
                apiBaseUrl.replaceAll("/$", ""),
                branding,
                deepLinks,
                platformStoreLinks,
                apps
        );
    }

    private PublicMobileAppResponse buildApp(
            String role,
            String defaultName,
            String slug,
            PublicMobileStoreLinksResponse platformStoreLinks,
            MobileSettingsResponse mobile
    ) {
        MobileAppSettingsDto tenantApp = mobile.apps().get(role);
        if (tenantApp != null) {
            return new PublicMobileAppResponse(
                    role,
                    tenantApp.name(),
                    tenantApp.bundleId(),
                    tenantApp.whiteLabel(),
                    slug,
                    platformStoreLinks
            );
        }

        String bundleId = switch (role) {
            case "shopper" -> platformShopperBundleId;
            case "cashier" -> platformCashierBundleId;
            case "grocery" -> platformGroceryBundleId;
            case "admin" -> platformAdminBundleId;
            case "stock" -> platformStockBundleId;
            default -> platformCashierBundleId;
        };

        return new PublicMobileAppResponse(
                role,
                defaultName,
                bundleId,
                false,
                slug,
                platformStoreLinks
        );
    }

    private PublicMobileStoreLinksResponse resolvePlatformStoreLinks(MobileSettingsResponse mobile) {
        String ios = blankToNull(platformIosStoreUrl);
        String android = blankToNull(platformAndroidStoreUrl);
        if (mobile.provisioned() && mobile.storeLinks() != null) {
            if (mobile.storeLinks().ios() != null && !mobile.storeLinks().ios().isBlank()) {
                ios = mobile.storeLinks().ios().trim();
            }
            if (mobile.storeLinks().android() != null && !mobile.storeLinks().android().isBlank()) {
                android = mobile.storeLinks().android().trim();
            }
        }
        return new PublicMobileStoreLinksResponse(ios, android);
    }

    private String staffAppName(String displayName, String role) {
        return switch (role) {
            case "cashier" -> displayName + " Cashier";
            case "grocery" -> displayName + " Grocery";
            case "admin" -> displayName + " Admin";
            case "stock" -> displayName + " Stock";
            default -> displayName;
        };
    }

    private PublicMobileDeepLinksResponse buildDeepLinks(String slug, String tenantHost) {
        String scheme = deepLinkScheme.trim();
        String shopPath = scheme + "://shop/" + slug;
        String loginPath = scheme + "://login/" + slug;
        String tenantPath = scheme + "://tenant/" + slug;

        String universalBase = universalLinkScheme + "://" + tenantHost;
        return new PublicMobileDeepLinksResponse(
                shopPath,
                loginPath,
                loginPath,
                loginPath,
                loginPath,
                tenantPath,
                universalBase + "/shop",
                universalBase + "/app"
        );
    }

    private String resolveTenantHost(Business business) {
        return domainMappingRepository.findByBusinessIdAndDeletedAtIsNull(business.getId()).stream()
                .filter(DomainMapping::isPrimary)
                .map(DomainMapping::getDomain)
                .findFirst()
                .orElseGet(() -> fallbackSlugHost(business.getSlug()));
    }

    private String fallbackSlugHost(String slug) {
        String suffix = slugDomainSuffix == null ? "" : slugDomainSuffix.trim().toLowerCase(Locale.ROOT);
        if (suffix.isEmpty() || slug == null || slug.isBlank()) {
            return slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        }
        return slug.trim().toLowerCase(Locale.ROOT) + "." + suffix;
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
