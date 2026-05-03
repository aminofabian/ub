package zelisline.ub.storefront.application;

import java.util.List;

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
    public WebOrderDetailResponse getOrder(String businessId, String orderId) {
        WebOrder o = webOrderRepository
                .findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
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
}
