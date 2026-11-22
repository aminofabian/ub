package zelisline.ub.marketplace.application;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.OpenSupplierShopRequest;
import zelisline.ub.marketplace.api.dto.SupplierShopResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.api.dto.PublicHostResolveResponse;
import zelisline.ub.tenancy.application.PublicHostResolverService;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * "Open your wholesale shop" (ADR 0038, P1). A supplier's passport gets a business of its own: the
 * same kind of tenant every merchant has, on the same plans, with the {@code supplier} format added.
 * The shop is created through the ordinary sign-up path, so it gets its address, ledger accounts and
 * item types exactly as any new shop does. Nothing is copied into it here, and no buyer's records
 * are touched.
 */
@Service
@RequiredArgsConstructor
public class SupplierShopService {

    /** A wholesaler sells goods; "other" is the neutral goods format, "supplier" marks the shop. */
    private static final List<String> NEW_SHOP_FORMATS = List.of("other", "supplier");

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final PublicHostResolverService publicHostResolverService;
    private final BusinessRepository businessRepository;
    private final TenancyService tenancyService;

    @Value("${app.tenancy.slug-domain-suffix:}")
    private String slugDomainSuffix;

    @Transactional(readOnly = true)
    public SupplierShopResponse current(String marketplaceSupplierId) {
        MarketplaceSupplier passport = requirePassport(marketplaceSupplierId);
        return describe(passport.getBusinessId());
    }

    /** Opens the shop, or returns the one already open: asking twice never makes two. */
    @Transactional
    public SupplierShopResponse open(String marketplaceSupplierId, OpenSupplierShopRequest request) {
        MarketplaceSupplier passport = requirePassport(marketplaceSupplierId);
        if (passport.getBusinessId() != null && !passport.getBusinessId().isBlank()) {
            return describe(passport.getBusinessId());
        }
        if (!MarketplaceSupplierStatuses.ACTIVE.equalsIgnoreCase(passport.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Your supplier account must be active before you open a shop");
        }

        // The address the supplier typed is theirs to keep or to be told is taken. With none typed,
        // the passport username is used when it is free, so /s/{username} and the shop match; when
        // a shop already holds it, the address is made from the supplier's name instead.
        String address = blankToNull(request != null ? request.shopAddress() : null);
        if (address == null) {
            String username = blankToNull(passport.getUsername());
            if (username != null
                    && !businessRepository.existsBySlugAndDeletedAtIsNull(username.toLowerCase(Locale.ROOT))) {
                address = username;
            }
        }

        PublicHostResolveResponse created = publicHostResolverService.onboardBusiness(
                passport.getName(), platformHost(), null, address);
        tenancyService.changeStoreTypesForTenant(created.tenantId(), NEW_SHOP_FORMATS);

        passport.setBusinessId(created.tenantId());
        marketplaceSupplierRepository.save(passport);
        return describe(created.tenantId());
    }

    private SupplierShopResponse describe(String businessId) {
        if (businessId == null || businessId.isBlank()) {
            return SupplierShopResponse.none();
        }
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId).orElse(null);
        if (business == null) {
            return SupplierShopResponse.none();
        }
        String suffix = blankToNull(slugDomainSuffix);
        String host = suffix == null ? null : business.getSlug() + "." + suffix.toLowerCase(Locale.ROOT);
        return new SupplierShopResponse(true, business.getId(), business.getSlug(), business.getName(), host);
    }

    /** Sign-up only uses the host for its log line; the shop's address comes from the slug. */
    private String platformHost() {
        String suffix = blankToNull(slugDomainSuffix);
        return suffix == null ? "localhost" : suffix.toLowerCase(Locale.ROOT);
    }

    private MarketplaceSupplier requirePassport(String marketplaceSupplierId) {
        return marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
