package zelisline.ub.marketplace.application;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.ClaimSupplierUsernameRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalPaymentDetailsRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProfileRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalPaymentDetailsResponse;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalVisibilityRequest;
import zelisline.ub.marketplace.api.dto.ShopSupplierPageResponse;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;

/**
 * The marketplace page of a supplier's shop, managed from inside the shop by its owner or admin. It reaches the
 * page through the shop it is linked to and then uses the supplier portal's own profile service, so both doors
 * edit the same page by the same rules. Nobody's sign-in changes.
 */
@Service
@RequiredArgsConstructor
public class ShopSupplierPageService {

    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final SupplierPortalProfileService profileService;
    private final SupplierPortalPaymentDetailsService paymentDetailsService;

    @Transactional(readOnly = true)
    public ShopSupplierPageResponse get(String businessId) {
        return describe(requirePage(businessId));
    }

    @Transactional
    public ShopSupplierPageResponse updateProfile(String businessId, PatchSupplierPortalProfileRequest request) {
        MarketplaceSupplier page = requirePage(businessId);
        profileService.updateProfile(page.getId(), request);
        return describe(requirePage(businessId));
    }

    @Transactional
    public ShopSupplierPageResponse updateVisibility(String businessId, PatchSupplierPortalVisibilityRequest request) {
        MarketplaceSupplier page = requirePage(businessId);
        profileService.updateVisibility(page.getId(), request);
        return describe(requirePage(businessId));
    }

    @Transactional
    public ShopSupplierPageResponse claimUsername(String businessId, ClaimSupplierUsernameRequest request) {
        MarketplaceSupplier page = requirePage(businessId);
        profileService.claimUsername(page.getId(), request);
        return describe(requirePage(businessId));
    }

    /**
     * Puts the page on the marketplace. Buyers need a way to reach the supplier and to know where it delivers, so
     * both are asked for first. A listed page stays listed: a supplier hides its prices or contacts instead.
     */
    @Transactional
    public ShopSupplierPageResponse list(String businessId) {
        MarketplaceSupplier page = requirePage(businessId);
        if (!page.isListed()) {
            var profile = profileService.getProfile(page.getId());
            if (profile.contactPhone() == null || profile.contactPhone().isBlank()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Add a contact phone number first");
            }
            if (profile.deliveryRegions() == null || profile.deliveryRegions().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Say where you deliver first");
            }
            page.setListed(true);
            marketplaceSupplierRepository.save(page);
        }
        return describe(page);
    }

    /** How the supplier is paid. The portal's own service reads and saves it, so the rules are the same. */
    @Transactional(readOnly = true)
    public SupplierPortalPaymentDetailsResponse payout(String businessId) {
        return paymentDetailsService.get(requirePage(businessId).getId());
    }

    /**
     * Saved by the shop user; their id is the actor in the audit record. As in the portal, a changed payout
     * phone clears its verification on the shops that buy from this supplier.
     */
    @Transactional
    public SupplierPortalPaymentDetailsResponse updatePayout(
            String businessId, String shopUserId, PatchSupplierPortalPaymentDetailsRequest request) {
        return paymentDetailsService.patch(requirePage(businessId).getId(), shopUserId, request);
    }

    private ShopSupplierPageResponse describe(MarketplaceSupplier page) {
        return new ShopSupplierPageResponse(
                profileService.getProfile(page.getId()), page.isListed(), page.isHidePrices(), page.isHideContacts());
    }

    private MarketplaceSupplier requirePage(String businessId) {
        return marketplaceSupplierRepository.findFirstByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "This shop has no marketplace page"));
    }
}
