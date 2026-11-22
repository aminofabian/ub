package zelisline.ub.marketplace.application;

import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.domain.MarketplaceSupplierStatuses;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * A shop that chose "Supplier" when it signed up. The other way in is the supplier portal, where the marketplace
 * page comes first and the shop second ({@link SupplierShopService}); here the shop comes first, so the platform
 * makes its marketplace page: active, linked to the shop, and not listed until the supplier is ready.
 */
@Service
@RequiredArgsConstructor
public class SupplierSignupService {

    private static final String SUPPLIER_FORMAT = "supplier";
    private static final String NEUTRAL_GOODS_FORMAT = "other";

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final MarketplaceSupplierPassportService passportService;
    private final BusinessRepository businessRepository;
    private final TenancyService tenancyService;

    /** Makes the shop a supplier's shop with its own marketplace page. Asking twice never makes two pages. */
    @Transactional
    public String becomeSupplier(String businessId) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));

        MarketplaceSupplier passport = marketplaceSupplierRepository.findFirstByBusinessId(businessId).orElse(null);
        if (passport == null) {
            passport = passportService.createDraftPassport(business.getName(), null, null, null);
            passport.setStatus(MarketplaceSupplierStatuses.ACTIVE);
            passport.setListed(false);
            passport.setBusinessId(businessId);
            marketplaceSupplierRepository.save(passport);
        }

        List<String> formats = new ArrayList<>(tenancyService.currentStoreTypesForTenant(businessId));
        if (!StoreTypeCodes.isSupplierBusiness(formats)) {
            if (formats.isEmpty()) {
                formats.add(NEUTRAL_GOODS_FORMAT);
            }
            formats.add(SUPPLIER_FORMAT);
            tenancyService.changeStoreTypesForTenant(businessId, formats);
        }
        return passport.getId();
    }
}
