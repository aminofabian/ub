package zelisline.ub.storefront.application;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.api.dto.PublicOrderTrackingResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Guest order lookup by canonical short code (scope D11 / §15). The code is a
 * UUID suffix, so we scan the shop's recent orders and match in Java — per-shop
 * storefront volume is low and a bounded scan is simpler than a derived column
 * in V1.
 */
@Service
@RequiredArgsConstructor
public class PublicWebOrderTrackingService {

    private static final int RECENT_ORDERS_SCAN = 300;

    private final BusinessRepository businessRepository;
    private final WebOrderRepository webOrderRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public PublicOrderTrackingResponse trackByCode(String slug, String code, String phoneLast4) {
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
        String businessId = business.getId();
        String key = code == null ? "" : code.trim();

        WebOrder found = webOrderRepository
                .findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, RECENT_ORDERS_SCAN))
                .stream()
                .filter(o -> WebOrderCodes.matches(key, o.getId()))
                .findFirst()
                .orElse(null);
        if (found == null || !phoneMatches(found.getCustomerPhone(), phoneLast4)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(found.getCatalogBranchId(), businessId)
                .map(Branch::getName)
                .orElse("(branch)");
        return new PublicOrderTrackingResponse(
                found.getId(),
                WebOrderCodes.code(found.getId()),
                found.getStatus(),
                found.getFulfillmentStatus(),
                found.getGrandTotal(),
                found.getCurrency(),
                branchName,
                found.getCreatedAt());
    }

    private static boolean phoneMatches(String storedPhone, String phoneLast4) {
        if (phoneLast4 == null || phoneLast4.isBlank()) {
            return false;
        }
        String stored = storedPhone == null ? "" : storedPhone.replaceAll("\\D", "");
        String provided = phoneLast4.replaceAll("\\D", "");
        return provided.length() == 4
                && stored.length() >= 4
                && stored.endsWith(provided);
    }
}
