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
 *
 * <p>Two gates: code + phone last-4 (legacy), or code + the Phase 5 one-tap
 * receipt token (single-use, 15-min TTL — see {@link ReceiptTokenService}).
 * Both failures surface as the same generic miss so the API never confirms
 * whether an order exists (§12 posture).
 */
@Service
@RequiredArgsConstructor
public class PublicWebOrderTrackingService {

    private static final int RECENT_ORDERS_SCAN = 300;

    private final BusinessRepository businessRepository;
    private final WebOrderRepository webOrderRepository;
    private final BranchRepository branchRepository;
    private final ReceiptTokenService receiptTokenService;

    @Transactional(readOnly = true)
    public PublicOrderTrackingResponse trackByCode(String slug, String code, String phoneLast4) {
        WebOrder found = resolveByCode(slug, code);
        if (found == null || !phoneMatches(found.getCustomerPhone(), phoneLast4)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return toResponse(found, false);
    }

    @Transactional
    public PublicOrderTrackingResponse trackByToken(String slug, String code, String rawToken) {
        WebOrder found = resolveByCode(slug, code);
        if (found == null || !receiptTokenService.verifyAndConsume(found, rawToken)) {
            // Generic on purpose: never distinguish unknown order from bad/used/expired token.
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return toResponse(found, true);
    }

    private WebOrder resolveByCode(String slug, String code) {
        Business business = businessRepository.findBySlugAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Store not found"));
        String key = code == null ? "" : code.trim();
        return webOrderRepository
                .findByBusinessIdOrderByCreatedAtDesc(business.getId(), PageRequest.of(0, RECENT_ORDERS_SCAN))
                .stream()
                .filter(o -> WebOrderCodes.matches(key, o.getId()))
                .findFirst()
                .orElse(null);
    }

    private PublicOrderTrackingResponse toResponse(WebOrder found, boolean receiptVerified) {
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(found.getCatalogBranchId(), found.getBusinessId())
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
                found.getCreatedAt(),
                receiptVerified ? found.getCustomerPhone() : null,
                receiptVerified ? Boolean.TRUE : null);
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
