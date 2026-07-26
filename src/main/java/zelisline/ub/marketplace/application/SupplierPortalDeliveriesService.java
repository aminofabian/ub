package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalDeliveryRow;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalDeliveriesService {

    private static final int QTY_SCALE = 4;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final BusinessRepository businessRepository;

    @Transactional(readOnly = true)
    public List<SupplierPortalDeliveryRow> listDeliveries(String marketplaceSupplierId) {
        return purchaseOrderRepository.findSupplierPortalInbox(marketplaceSupplierId).stream()
                .filter(this::isDeliveryRelevant)
                .map(this::toRow)
                .toList();
    }

    private boolean isDeliveryRelevant(PurchaseOrder po) {
        String status = po.getDeliveryStatus();
        if (status == null || status.isBlank()) {
            return false;
        }
        if (PurchasingConstants.DELIVERY_IN_TRANSIT.equals(status)
                || PurchasingConstants.DELIVERY_DELIVERED.equals(status)) {
            return true;
        }
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        return lines.stream().anyMatch(line ->
                line.getQtyReceived() != null && line.getQtyReceived().signum() > 0);
    }

    private SupplierPortalDeliveryRow toRow(PurchaseOrder po) {
        String businessName = businessRepository.findById(po.getBusinessId())
                .map(b -> b.getName())
                .orElse("Business");
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        BigDecimal ordered = BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        BigDecimal received = BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        for (PurchaseOrderLine line : lines) {
            if (line.getQtyOrdered() != null) {
                ordered = ordered.add(line.getQtyOrdered());
            }
            if (line.getQtyReceived() != null) {
                received = received.add(line.getQtyReceived());
            }
        }
        return new SupplierPortalDeliveryRow(
                po.getId(),
                po.getBusinessId(),
                businessName,
                po.getPoNumber(),
                po.getExpectedDate(),
                po.getSentToSupplierAt(),
                po.getSupplierResponseAt(),
                po.getUpdatedAt(),
                po.getDeliveryStatus(),
                po.getStatus(),
                ordered,
                received);
    }
}
