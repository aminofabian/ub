package zelisline.ub.marketplace.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderDetailResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderListRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalRespondRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalShipRequest;
import zelisline.ub.marketplace.domain.SupplierPerformanceEvent;
import zelisline.ub.marketplace.repository.SupplierPerformanceEventRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalOrdersService {

    private static final int UNIT_SCALE = 4;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final SupplierPerformanceEventRepository performanceEventRepository;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<SupplierPortalOrderListRow> listOrders(String marketplaceSupplierId) {
        return purchaseOrderRepository.findSupplierPortalInbox(marketplaceSupplierId).stream()
                .map(po -> {
                    String businessName = businessRepository.findById(po.getBusinessId())
                            .map(b -> b.getName())
                            .orElse("Business");
                    int lineCount = purchaseOrderLineRepository
                            .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId()).size();
                    return new SupplierPortalOrderListRow(
                            po.getId(),
                            po.getBusinessId(),
                            businessName,
                            po.getPoNumber(),
                            po.getExpectedDate(),
                            po.getStatus(),
                            po.getSentToSupplierAt(),
                            po.getSupplierResponseAt(),
                            po.getDeliveryStatus(),
                            lineCount);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public SupplierPortalOrderDetailResponse getOrder(String marketplaceSupplierId, String purchaseOrderId) {
        PurchaseOrder po = requirePortalOrder(marketplaceSupplierId, purchaseOrderId);
        return toDetail(po);
    }

    @Transactional
    public SupplierPortalOrderDetailResponse respond(
            String marketplaceSupplierId,
            String purchaseOrderId,
            SupplierPortalRespondRequest request
    ) {
        PurchaseOrder po = requirePortalOrder(marketplaceSupplierId, purchaseOrderId);
        if (po.getSupplierResponseAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Purchase order already has a supplier response");
        }
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        Map<String, PurchaseOrderLine> lineById = lines.stream()
                .collect(Collectors.toMap(PurchaseOrderLine::getId, Function.identity()));
        if (request.lines().size() != lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Response must include every purchase order line");
        }
        Set<String> seen = new HashSet<>();
        for (SupplierPortalRespondRequest.LineResponse input : request.lines()) {
            if (!seen.add(input.purchaseOrderLineId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate line in response");
            }
            PurchaseOrderLine line = lineById.get(input.purchaseOrderLineId());
            if (line == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid purchase order line");
            }
            applyLineResponse(line, input);
            purchaseOrderLineRepository.save(line);
        }
        po.setSupplierResponseAt(Instant.now());
        purchaseOrderRepository.save(po);
        logPerformanceEvent(marketplaceSupplierId, po.getBusinessId(), "po_supplier_responded", po.getId());
        return toDetail(po);
    }

    @Transactional
    public SupplierPortalOrderDetailResponse ship(
            String marketplaceSupplierId,
            String purchaseOrderId,
            SupplierPortalShipRequest request
    ) {
        PurchaseOrder po = requirePortalOrder(marketplaceSupplierId, purchaseOrderId);
        if (po.getSupplierResponseAt() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Respond to the order before updating delivery");
        }
        po.setDeliveryStatus(request.deliveryStatus());
        if (request.trackingNote() != null && !request.trackingNote().isBlank()) {
            String existing = po.getNotes() == null ? "" : po.getNotes().trim();
            String tracking = "[Supplier tracking] " + request.trackingNote().trim();
            po.setNotes(existing.isBlank() ? tracking : existing + "\n" + tracking);
        }
        purchaseOrderRepository.save(po);
        logPerformanceEvent(marketplaceSupplierId, po.getBusinessId(), "po_delivery_" + request.deliveryStatus(), po.getId());
        return toDetail(po);
    }

    private void applyLineResponse(PurchaseOrderLine line, SupplierPortalRespondRequest.LineResponse input) {
        String status = input.supplierLineStatus().trim();
        BigDecimal ordered = line.getQtyOrdered().setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        switch (status) {
            case PurchasingConstants.SUPPLIER_LINE_ACCEPTED -> {
                BigDecimal accepted = input.qtyAccepted() == null ? ordered : scaleQty(input.qtyAccepted());
                if (accepted.compareTo(ordered) != 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Accepted quantity must equal ordered quantity");
                }
                line.setSupplierLineStatus(PurchasingConstants.SUPPLIER_LINE_ACCEPTED);
                line.setQtyAccepted(accepted);
            }
            case PurchasingConstants.SUPPLIER_LINE_REJECTED -> {
                line.setSupplierLineStatus(PurchasingConstants.SUPPLIER_LINE_REJECTED);
                line.setQtyAccepted(BigDecimal.ZERO.setScale(UNIT_SCALE, RoundingMode.HALF_UP));
            }
            case PurchasingConstants.SUPPLIER_LINE_PARTIALLY_ACCEPTED -> {
                if (input.qtyAccepted() == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Partial acceptance requires qtyAccepted");
                }
                BigDecimal accepted = scaleQty(input.qtyAccepted());
                if (accepted.signum() <= 0 || accepted.compareTo(ordered) >= 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Partial quantity must be greater than zero and less than ordered quantity");
                }
                line.setSupplierLineStatus(PurchasingConstants.SUPPLIER_LINE_PARTIALLY_ACCEPTED);
                line.setQtyAccepted(accepted);
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid supplier line status");
        }
        line.setSupplierNote(blankToNull(input.supplierNote()));
    }

    private SupplierPortalOrderDetailResponse toDetail(PurchaseOrder po) {
        String businessName = businessRepository.findById(po.getBusinessId())
                .map(b -> b.getName())
                .orElse("Business");
        List<PurchaseOrderLine> lines = purchaseOrderLineRepository
                .findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        List<SupplierPortalOrderDetailResponse.SupplierPortalOrderLineResponse> lineRows = lines.stream()
                .map(line -> {
                    Item item = itemRepository.findById(line.getItemId()).orElse(null);
                    return new SupplierPortalOrderDetailResponse.SupplierPortalOrderLineResponse(
                            line.getId(),
                            line.getItemId(),
                            item == null ? line.getItemId() : item.getName(),
                            item == null ? null : item.getSku(),
                            line.getQtyOrdered(),
                            line.getQtyReceived(),
                            line.getUnitEstimatedCost(),
                            line.getSupplierLineStatus(),
                            line.getQtyAccepted(),
                            line.getSupplierNote());
                })
                .toList();
        return new SupplierPortalOrderDetailResponse(
                po.getId(),
                po.getBusinessId(),
                businessName,
                po.getPoNumber(),
                po.getExpectedDate(),
                po.getStatus(),
                po.getNotes(),
                po.getSentToSupplierAt(),
                po.getSupplierResponseAt(),
                po.getDeliveryStatus(),
                lineRows);
    }

    private PurchaseOrder requirePortalOrder(String marketplaceSupplierId, String purchaseOrderId) {
        return purchaseOrderRepository.findSupplierPortalOrder(marketplaceSupplierId, purchaseOrderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
    }

    private void logPerformanceEvent(
            String marketplaceSupplierId,
            String businessId,
            String eventType,
            String purchaseOrderId
    ) {
        try {
            SupplierPerformanceEvent event = new SupplierPerformanceEvent();
            event.setMarketplaceSupplierId(marketplaceSupplierId);
            event.setBusinessId(businessId);
            event.setEventType(eventType);
            event.setPayloadJson(objectMapper.writeValueAsString(Map.of("purchaseOrderId", purchaseOrderId)));
            performanceEventRepository.save(event);
        } catch (Exception ignored) {
            // Instrumentation must not block order workflow.
        }
    }

    private static BigDecimal scaleQty(BigDecimal qty) {
        return qty.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
