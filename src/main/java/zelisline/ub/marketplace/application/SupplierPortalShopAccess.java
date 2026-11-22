package zelisline.ub.marketplace.application;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.platform.security.SupplierPrincipal;
import zelisline.ub.suppliers.domain.Supplier;

/**
 * Answers one question for pages outside the supplier portal: is the caller a signed-in supplier
 * that this shop has connected, looking at its own row in that shop? (ADR 0038, P0.2.)
 */
@Service
@RequiredArgsConstructor
public class SupplierPortalShopAccess {

    private final BusinessSupplierConnectionRepository connectionRepository;

    @Transactional(readOnly = true)
    public boolean currentSupplierIsConnectedTo(Supplier shopSupplier) {
        if (shopSupplier == null || shopSupplier.getId() == null || shopSupplier.getBusinessId() == null) {
            return false;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof SupplierPrincipal supplier)) {
            return false;
        }
        String passportId = supplier.marketplaceSupplierId();
        if (passportId == null || passportId.isBlank()) {
            return false;
        }
        return connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(shopSupplier.getBusinessId(), passportId)
                .filter(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                .filter(c -> shopSupplier.getId().equals(c.getLocalSupplierId())
                        || passportId.equals(shopSupplier.getMarketplaceSupplierId()))
                .isPresent();
    }
}
