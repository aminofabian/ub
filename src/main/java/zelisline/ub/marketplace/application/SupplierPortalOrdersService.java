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
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalOrderRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderDetailResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalOrderListRow;
import zelisline.ub.marketplace.api.dto.SupplierPortalRespondRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalShipRequest;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.SupplierPerformanceEvent;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.SupplierPerformanceEventRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class SupplierPortalOrdersService {

    private static final int UNIT_SCALE = 4;
    private static final BigDecimal MIN_UNIT_COST = new BigDecimal("0.01");

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final SupplierPerformanceEventRepository performanceEventRepository;
    private final SupplierPortalShopLinkService shopLinkService;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final PathAPurchaseService pathAPurchaseService;
    private final BranchResolutionService branchResolutionService;
    private final MarketplaceEscrowService marketplaceEscrowService;
    private final ObjectMapper objectMapper;
    private final zelisline.ub.marketplace.repository.MarketplaceSupplierRepository marketplaceSupplierRepository;

    @Transactional
    public List<SupplierPortalOrderListRow> listOrders(String marketplaceSupplierId) {
        try {
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft heal — still return whatever inbox exists.
        }
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

    /** Unsent proposals one supplier may leave with one shop supplier before the shop acts on them. */
    static final int MAX_OPEN_PROPOSALS_PER_SHOP = 5;

    /**
     * Supplier-taken order for a connected shop: a <em>proposal</em>. It is written as the shop's
     * draft Path A PO and nothing more — never sent, never accepted — so an outsider cannot book a
     * committed order into a shop's books (ADR 0038, P0.3). The shop sends it from its own side;
     * only then does the supplier respond to it like any other order.
     */
    @Transactional
    public SupplierPortalOrderDetailResponse createOrder(
            String marketplaceSupplierId,
            CreateSupplierPortalOrderRequest request
    ) {
        // A supplier with its own shop takes orders there and sends them across the trade lane
        // (ADR 0041), where the shop confirms and writes its own purchase order. Writing a draft
        // into the shop's books is kept only for a supplier that has no shop (P2-3, decision D10).
        boolean hasOwnShop = marketplaceSupplierRepository.findById(marketplaceSupplierId)
                .map(passport -> passport.getBusinessId() != null && !passport.getBusinessId().isBlank())
                .orElse(false);
        if (hasOwnShop) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Take this order in your shop's trade desk, then send it to the shop from there.");
        }
        try {
            shopLinkService.ensureLinksAndCatalogue(marketplaceSupplierId);
        } catch (RuntimeException ignored) {
            // Soft heal.
        }

        BusinessSupplierConnection link = connectionRepository
                .findByMarketplaceSupplierIdAndLocalSupplierId(
                        marketplaceSupplierId, request.localSupplierId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found"));
        if (!BusinessSupplierConnectionStatuses.ACTIVE.equals(link.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Shop link not found");
        }

        String businessId = link.getBusinessId();
        String localSupplierId = link.getLocalSupplierId();
        long openProposals = purchaseOrderRepository.countByBusinessIdAndSupplierIdAndStatusAndSource(
                businessId,
                localSupplierId,
                PurchasingConstants.PO_DRAFT,
                PurchasingConstants.PO_SOURCE_SUPPLIER_PORTAL);
        if (openProposals >= MAX_OPEN_PROPOSALS_PER_SHOP) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "This shop still has orders from you waiting for its confirmation. "
                            + "Ask the shop to confirm or cancel them first.");
        }
        String branchId = branchResolutionService.resolveDefaultBranch(businessId);

        Set<String> seenItems = new HashSet<>();
        for (CreateSupplierPortalOrderRequest.Line line : request.lines()) {
            if (!seenItems.add(line.itemId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Duplicate item in order");
            }
            SupplierProduct productLink = supplierProductRepository
                    .findBySupplierIdAndItemId(localSupplierId, line.itemId())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Item is not linked to this shop supplier"));
            if (productLink.getDeletedAt() != null || !productLink.isActive()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item link is inactive");
            }
        }

        String notes = blankToNull(request.notes());
        String portalNote = "[Taken via supplier portal]";
        String mergedNotes = notes == null ? portalNote : portalNote + "\n" + notes;

        var created = pathAPurchaseService.createPurchaseOrder(
                businessId,
                new CreatePathAPurchaseOrderRequest(
                        localSupplierId,
                        branchId,
                        request.expectedDate(),
                        null,
                        mergedNotes));

        Map<String, SupplierProduct> linksByItem = request.lines().stream()
                .map(CreateSupplierPortalOrderRequest.Line::itemId)
                .distinct()
                .map(itemId -> supplierProductRepository.findBySupplierIdAndItemId(localSupplierId, itemId).orElseThrow())
                .collect(Collectors.toMap(SupplierProduct::getItemId, Function.identity(), (a, b) -> a));

        for (CreateSupplierPortalOrderRequest.Line line : request.lines()) {
            SupplierProduct productLink = linksByItem.get(line.itemId());
            BigDecimal unitCost = resolveUnitCost(line.unitEstimatedCost(), productLink);
            pathAPurchaseService.addPurchaseOrderLine(
                    businessId,
                    created.id(),
                    new AddPathAPurchaseOrderLineRequest(
                            line.itemId(),
                            scaleQty(line.qtyOrdered()),
                            unitCost));
        }

        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(created.id(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Order missing"));
        // Stays the shop's draft: no sent time, no supplier response, lines left pending.
        po.setSource(PurchasingConstants.PO_SOURCE_SUPPLIER_PORTAL);
        purchaseOrderRepository.save(po);

        logPerformanceEvent(marketplaceSupplierId, businessId, "po_supplier_taken", po.getId());
        return toDetail(po);
    }

    @Transactional
    public SupplierPortalOrderDetailResponse respond(
            String marketplaceSupplierId,
            String purchaseOrderId,
            SupplierPortalRespondRequest request
    ) {
        PurchaseOrder po = requirePortalOrder(marketplaceSupplierId, purchaseOrderId);
        requireSentByShop(po);
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
        requireSentByShop(po);
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
        marketplaceEscrowService.onDeliveryStatusChanged(
                po.getBusinessId(), po.getId(), request.deliveryStatus());
        logPerformanceEvent(marketplaceSupplierId, po.getBusinessId(), "po_delivery_" + request.deliveryStatus(), po.getId());
        return toDetail(po);
    }

    private BigDecimal resolveUnitCost(BigDecimal requested, SupplierProduct productLink) {
        if (requested != null && requested.signum() > 0) {
            return requested.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        }
        if (productLink.getDefaultCostPrice() != null && productLink.getDefaultCostPrice().signum() > 0) {
            return productLink.getDefaultCostPrice().setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        }
        if (productLink.getLastCostPrice() != null && productLink.getLastCostPrice().signum() > 0) {
            return productLink.getLastCostPrice().setScale(UNIT_SCALE, RoundingMode.HALF_UP);
        }
        return MIN_UNIT_COST.setScale(UNIT_SCALE, RoundingMode.HALF_UP);
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
                            line.getSupplierNote(),
                            line.getPurchaseUnit(),
                            line.getPurchaseQty(),
                            line.getUnitsPerPurchaseUnit(),
                            line.getPackPrice(),
                            line.getBaseUnit());
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

    /** A proposal the shop has not sent yet is read-only to the supplier. */
    private static void requireSentByShop(PurchaseOrder po) {
        if (po.getSentToSupplierAt() == null || PurchasingConstants.PO_DRAFT.equals(po.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "The shop has not confirmed this order yet");
        }
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
