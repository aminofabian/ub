package zelisline.ub.marketplace.application;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderSupplierResponse;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.repository.SupplierRepository;

@Service
@RequiredArgsConstructor
public class MarketplacePurchaseOrderService {

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final SupplierRepository supplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final PathAPurchaseService pathAPurchaseService;

    @Transactional
    public PathAPurchaseOrderDetailResponse sendToSupplier(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        if (!PurchasingConstants.PO_DRAFT.equals(po.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order must be in draft status");
        }
        long lineCount = purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId()).size();
        if (lineCount == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase order has no lines");
        }

        Supplier supplier = supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(po.getSupplierId(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Supplier not found"));
        if (supplier.getMarketplaceSupplierId() == null || supplier.getMarketplaceSupplierId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Supplier is not connected to the marketplace portal");
        }
        BusinessSupplierConnection connection = connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(businessId, supplier.getMarketplaceSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No active marketplace connection for this supplier"));
        if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(connection.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Marketplace connection is not active");
        }

        po.setStatus(PurchasingConstants.PO_SENT);
        po.setSentToSupplierAt(Instant.now());
        po.setDeliveryStatus(PurchasingConstants.DELIVERY_NOT_SHIPPED);
        if (po.getSource() == null || po.getSource().isBlank()
                || PurchasingConstants.PO_SOURCE_MANUAL.equals(po.getSource())) {
            po.setSource(PurchasingConstants.PO_SOURCE_MARKETPLACE);
        }
        for (PurchaseOrderLine line : purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId())) {
            line.setSupplierLineStatus(PurchasingConstants.SUPPLIER_LINE_PENDING);
            line.setQtyAccepted(null);
            line.setSupplierNote(null);
            purchaseOrderLineRepository.save(line);
        }
        purchaseOrderRepository.save(po);
        return pathAPurchaseService.getPurchaseOrder(businessId, purchaseOrderId);
    }

    /** True when the local supplier is linked to a marketplace supplier with an active connection. */
    @Transactional(readOnly = true)
    public boolean isPortalConnected(String businessId, String supplierId) {
        return supplierRepository.findByIdAndBusinessIdAndDeletedAtIsNull(supplierId, businessId)
                .map(Supplier::getMarketplaceSupplierId)
                .filter(mid -> !mid.isBlank())
                .flatMap(mid -> connectionRepository.findByBusinessIdAndMarketplaceSupplierId(businessId, mid))
                .map(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public PathAPurchaseOrderSupplierResponse getSupplierResponse(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadPo(businessId, purchaseOrderId);
        if (po.getSentToSupplierAt() == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order was not sent to supplier portal");
        }
        PathAPurchaseOrderDetailResponse order = pathAPurchaseService.getPurchaseOrder(businessId, purchaseOrderId);
        return new PathAPurchaseOrderSupplierResponse(
                po.getId(),
                po.getSentToSupplierAt(),
                po.getSupplierResponseAt(),
                po.getDeliveryStatus(),
                order);
    }

    private PurchaseOrder loadPo(String businessId, String id) {
        return purchaseOrderRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
    }
}
