package zelisline.ub.storefront.application;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.storefront.api.dto.WebOrderDetailResponse;
import zelisline.ub.storefront.api.dto.WebOrderLineSnapshotResponse;
import zelisline.ub.storefront.api.dto.WebOrderSummaryResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class WebOrderAdminService {

    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final BranchRepository branchRepository;

    @Transactional(readOnly = true)
    public Page<WebOrderSummaryResponse> pageOrders(String businessId, Pageable pageable) {
        return webOrderRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable).map(o -> toSummary(businessId, o));
    }

    @Transactional(readOnly = true)
    public Page<WebOrderSummaryResponse> pageOrdersForShopperEmail(
            String businessId,
            String customerEmailNormalized,
            Pageable pageable
    ) {
        String key = safeEmailKey(customerEmailNormalized);
        return webOrderRepository.findShopperOrdersByBusinessIdAndNormalizedEmail(businessId, key, pageable)
                .map(o -> toSummary(businessId, o));
    }

    @Transactional(readOnly = true)
    public WebOrderDetailResponse getOrderForShopperEmail(
            String businessId,
            String orderId,
            String customerEmailNormalized
    ) {
        WebOrder o = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        assertEmailOwnership(o.getCustomerEmail(), customerEmailNormalized);
        return buildDetail(businessId, o);
    }

    @Transactional(readOnly = true)
    public WebOrderDetailResponse getOrder(String businessId, String orderId) {
        WebOrder o = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        return buildDetail(businessId, o);
    }

    private WebOrderDetailResponse buildDetail(String businessId, WebOrder o) {
        List<WebOrderLine> raw = webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(o.getId());
        List<WebOrderLineSnapshotResponse> lines = raw.stream()
                .map(l -> new WebOrderLineSnapshotResponse(
                        l.getItemId(),
                        l.getItemName(),
                        l.getVariantName(),
                        l.getQuantity(),
                        l.getUnitPrice(),
                        l.getLineTotal(),
                        l.getLineIndex()))
                .toList();
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(o.getCatalogBranchId(), businessId)
                .map(Branch::getName)
                .orElse("(branch)");
        return new WebOrderDetailResponse(
                o.getId(),
                o.getCartId(),
                o.getStatus(),
                o.getGrandTotal(),
                o.getCurrency(),
                o.getCatalogBranchId(),
                branchName,
                o.getCustomerName(),
                o.getCustomerPhone(),
                o.getCustomerEmail(),
                o.getNotes(),
                o.getCreatedAt(),
                lines);
    }

    private WebOrderSummaryResponse toSummary(String businessId, WebOrder o) {
        String branchName = branchRepository
                .findByIdAndBusinessIdAndDeletedAtIsNull(o.getCatalogBranchId(), businessId)
                .map(Branch::getName)
                .orElse("(branch)");
        return new WebOrderSummaryResponse(
                o.getId(),
                o.getStatus(),
                o.getGrandTotal(),
                o.getCurrency(),
                o.getCustomerName(),
                o.getCustomerPhone(),
                o.getCatalogBranchId(),
                branchName,
                o.getCreatedAt());
    }

    private static String safeEmailKey(String raw) {
        return raw == null ? "" : raw.trim();
    }

    /** Normalised lowercase email for JPQL compares (must match callers). */
    private static String lowerTrim(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static void assertEmailOwnership(String orderCustomerEmail, String signedInEmailNormalizedKey) {
        if (orderCustomerEmail == null || orderCustomerEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        if (!lowerTrim(orderCustomerEmail).equals(lowerTrim(signedInEmailNormalizedKey))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
    }
}
