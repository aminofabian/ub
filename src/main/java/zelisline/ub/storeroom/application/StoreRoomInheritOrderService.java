package zelisline.ub.storeroom.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.platform.security.CurrentUserPermissions;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.domain.PurchaseOrderLine;
import zelisline.ub.purchasing.repository.PurchaseOrderLineRepository;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.storeroom.api.dto.CreateStoreRoomMovementRequest;
import zelisline.ub.storeroom.api.dto.InheritOrderApplyResponse;
import zelisline.ub.storeroom.api.dto.InheritOrderPreviewResponse;
import zelisline.ub.storeroom.api.dto.InheritOrderRequest;
import zelisline.ub.storeroom.api.dto.StoreRoomMovementResponse;
import zelisline.ub.storeroom.domain.StoreItem;
import zelisline.ub.storeroom.domain.StoreRoomMode;
import zelisline.ub.storeroom.repository.StoreItemRepository;
import zelisline.ub.storeroom.repository.StoreRoomMovementRepository;

/**
 * Puts a purchase order into the store room as {@code from_purchase_order} memos.
 * Inventory is not raised here for connected mode — Confirm order / GRN already owns that.
 * Standalone mode still bumps the local register count.
 */
@Service
@RequiredArgsConstructor
public class StoreRoomInheritOrderService {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderLineRepository purchaseOrderLineRepository;
    private final ItemRepository itemRepository;
    private final StoreItemRepository storeItemRepository;
    private final StoreRoomMovementRepository movementRepository;
    private final StoreRoomSettingsService storeRoomSettingsService;
    private final StoreRoomMovementService storeRoomMovementService;
    private final CurrentUserPermissions permissions;

    @Transactional(readOnly = true)
    public InheritOrderPreviewResponse preview(String businessId, String purchaseOrderId) {
        PurchaseOrder po = loadEligiblePo(businessId, purchaseOrderId);
        List<PurchaseOrderLine> poLines =
                purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        Map<String, Item> products = loadProducts(businessId, poLines);
        List<StoreItem> register = storeItemRepository.findByBusinessIdOrderByNameAsc(businessId);
        return toPreview(businessId, po, poLines, products, register);
    }

    @Transactional
    public InheritOrderApplyResponse apply(
            String businessId,
            String actorId,
            String actorRoleId,
            String sessionBranchId,
            InheritOrderRequest request
    ) {
        permissions.requireAny("catalog.items.write", "inventory.write");

        PurchaseOrder po = loadEligiblePo(businessId, request.purchaseOrderId());
        boolean already = alreadyInherited(businessId, po.getId());
        if (already && !Boolean.TRUE.equals(request.confirmDuplicate())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "This order was already put into the store room. Confirm to apply it again.");
        }

        List<PurchaseOrderLine> poLines =
                purchaseOrderLineRepository.findByPurchaseOrderIdOrderBySortOrderAscIdAsc(po.getId());
        Map<String, PurchaseOrderLine> byId = new HashMap<>();
        for (PurchaseOrderLine line : poLines) {
            byId.put(line.getId(), line);
        }
        Map<String, Item> products = loadProducts(businessId, poLines);
        StoreRoomMode mode = storeRoomSettingsService.currentMode(businessId);
        boolean standalone = mode != StoreRoomMode.CONNECTED;

        int created = 0;
        List<StoreRoomMovementResponse> recorded = new ArrayList<>();
        String note = inheritNote(po);

        for (InheritOrderRequest.Line incoming : request.lines()) {
            PurchaseOrderLine poLine = byId.get(incoming.purchaseOrderLineId());
            if (poLine == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "That line is not on this purchase order.");
            }
            Item product = products.get(poLine.getItemId());
            if (product == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Product missing for a line on " + po.getPoNumber() + ".");
            }
            MatchedRow matched = matchOrCreate(businessId, product);
            if (matched.created()) {
                created++;
            }
            if (standalone) {
                applyLocalIncrease(matched.row(), incoming.quantity());
            }
            recorded.add(storeRoomMovementService.record(
                    businessId,
                    actorId,
                    actorRoleId,
                    sessionBranchId,
                    new CreateStoreRoomMovementRequest(
                            matched.row().getId(),
                            "in",
                            "from_purchase_order",
                            incoming.quantity(),
                            note,
                            po.getBranchId())));
        }

        return new InheritOrderApplyResponse(
                po.getId(),
                po.getPoNumber(),
                created,
                recorded.size(),
                recorded);
    }

    private record MatchedRow(StoreItem row, boolean created) {
    }

    private InheritOrderPreviewResponse toPreview(
            String businessId,
            PurchaseOrder po,
            List<PurchaseOrderLine> poLines,
            Map<String, Item> products,
            List<StoreItem> register
    ) {
        boolean unpacked = PurchasingConstants.PO_RECEIVED.equals(po.getStatus());
        List<InheritOrderPreviewResponse.Line> lines = new ArrayList<>();
        for (PurchaseOrderLine poLine : poLines) {
            Item product = products.get(poLine.getItemId());
            BigDecimal remaining = remaining(poLine);
            if (poLine.getQtyReceived() != null && poLine.getQtyReceived().compareTo(ZERO) > 0) {
                unpacked = true;
            }
            StoreItem matched = product == null ? null : match(register, product);
            BigDecimal factor = displayFactor(product);
            lines.add(new InheritOrderPreviewResponse.Line(
                    poLine.getId(),
                    poLine.getItemId(),
                    product == null ? "Unknown product" : product.getName(),
                    product == null ? null : product.getBarcode(),
                    poLine.getQtyOrdered(),
                    poLine.getQtyReceived() == null ? ZERO : poLine.getQtyReceived(),
                    remaining,
                    factor,
                    packUnit(product),
                    matched == null ? null : matched.getId(),
                    matched != null));
        }
        return new InheritOrderPreviewResponse(
                po.getId(),
                po.getPoNumber(),
                po.getStatus(),
                po.getDeliveryStatus(),
                po.getSupplierId(),
                po.getBranchId(),
                po.getExpectedDate(),
                po.getCreatedAt(),
                alreadyInherited(businessId, po.getId()),
                unpacked,
                lines);
    }

    private MatchedRow matchOrCreate(String businessId, Item product) {
        List<StoreItem> register = storeItemRepository.findByBusinessIdOrderByNameAsc(businessId);
        StoreItem existing = match(register, product);
        if (existing != null) {
            if (existing.getItemId() == null) {
                existing.setItemId(product.getId());
                storeItemRepository.save(existing);
            }
            return new MatchedRow(existing, false);
        }
        StoreItem row = new StoreItem();
        row.setBusinessId(businessId);
        row.setName(product.getName());
        row.setBarcode(borrowedBarcode(businessId, product));
        row.setItemId(product.getId());
        row.setQuantity(0);
        if (product.getBuyingPrice() != null) {
            row.setBuyingPrice(product.getBuyingPrice());
        }
        return new MatchedRow(storeItemRepository.save(row), true);
    }

    private static StoreItem match(List<StoreItem> register, Item product) {
        for (StoreItem row : register) {
            if (product.getId().equals(row.getItemId())) {
                return row;
            }
        }
        String barcode = product.getBarcode();
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        String needle = barcode.trim();
        for (StoreItem row : register) {
            if (row.getItemId() == null && needle.equalsIgnoreCase(row.getBarcode())) {
                return row;
            }
        }
        return null;
    }

    private String borrowedBarcode(String businessId, Item product) {
        String barcode = product.getBarcode();
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        String trimmed = barcode.trim();
        return storeItemRepository.existsByBusinessIdAndBarcode(businessId, trimmed) ? null : trimmed;
    }

    private void applyLocalIncrease(StoreItem item, BigDecimal quantity) {
        BigDecimal whole = quantity.stripTrailingZeros();
        if (whole.scale() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Back-room-only items are counted in whole numbers.");
        }
        int qty = whole.intValueExact();
        item.setQuantity(item.getQuantity() + qty);
        storeItemRepository.save(item);
    }

    private PurchaseOrder loadEligiblePo(String businessId, String purchaseOrderId) {
        if (purchaseOrderId == null || purchaseOrderId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Purchase order is required");
        }
        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(purchaseOrderId.trim(), businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Purchase order not found"));
        if (PurchasingConstants.PO_CANCELLED.equals(po.getStatus())
                || PurchasingConstants.PO_DRAFT.equals(po.getStatus())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Only sent or received orders can be put into the store room.");
        }
        return po;
    }

    private boolean alreadyInherited(String businessId, String purchaseOrderId) {
        return movementRepository.existsByBusinessIdAndNoteStartingWith(
                businessId, notePrefix(purchaseOrderId));
    }

    private static String inheritNote(PurchaseOrder po) {
        String number = po.getPoNumber() == null ? "" : po.getPoNumber().trim();
        String body = number.isEmpty() ? "Inherited from a purchase order" : "Inherited from PO " + number;
        String note = notePrefix(po.getId()) + " " + body;
        return note.length() <= 255 ? note : note.substring(0, 255);
    }

    private static String notePrefix(String purchaseOrderId) {
        return "[PO:" + purchaseOrderId + "]";
    }

    private Map<String, Item> loadProducts(String businessId, List<PurchaseOrderLine> lines) {
        List<String> ids = lines.stream().map(PurchaseOrderLine::getItemId).distinct().toList();
        Map<String, Item> byId = new HashMap<>();
        if (ids.isEmpty()) {
            return byId;
        }
        for (Item item : itemRepository.findAllById(ids)) {
            if (item.getDeletedAt() != null || !businessId.equals(item.getBusinessId())) {
                continue;
            }
            byId.put(item.getId(), item);
        }
        return byId;
    }

    private static BigDecimal remaining(PurchaseOrderLine line) {
        BigDecimal ordered = line.getQtyOrdered() == null ? ZERO : line.getQtyOrdered();
        BigDecimal received = line.getQtyReceived() == null ? ZERO : line.getQtyReceived();
        BigDecimal left = ordered.subtract(received);
        return left.compareTo(ZERO) > 0 ? left : ZERO;
    }

    private static BigDecimal displayFactor(Item product) {
        if (product == null || !product.isPackageVariant()) {
            return BigDecimal.ONE;
        }
        BigDecimal qty = product.getPackagingUnitQty();
        if (qty == null || qty.compareTo(BigDecimal.ONE) <= 0) {
            return BigDecimal.ONE;
        }
        return qty.setScale(4, RoundingMode.HALF_UP);
    }

    private static String packUnit(Item product) {
        if (product == null) {
            return "each";
        }
        String named = product.getPackagingUnitName();
        if (named != null && !named.isBlank()) {
            return named.trim();
        }
        return product.isPackageVariant() ? "pack" : "each";
    }
}
