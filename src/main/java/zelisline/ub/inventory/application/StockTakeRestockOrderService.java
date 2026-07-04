package zelisline.ub.inventory.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.api.dto.StockTakeRestockDtos.ConvertRestockOrderResponse;
import zelisline.ub.inventory.domain.StockTakeRestockItem;
import zelisline.ub.inventory.repository.StockTakeRestockItemRepository;
import zelisline.ub.inventory.restock.RestockOrderLineRow;
import zelisline.ub.inventory.restock.RestockOrderPdfRenderer;
import zelisline.ub.inventory.restock.RestockOrderSnapshot;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.api.dto.PathAPurchaseOrderDetailResponse;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.suppliers.domain.Supplier;
import zelisline.ub.suppliers.domain.SupplierContact;
import zelisline.ub.suppliers.repository.SupplierContactRepository;
import zelisline.ub.suppliers.repository.SupplierRepository;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class StockTakeRestockOrderService {

    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(DISPLAY_ZONE);

    private final StockTakeRestockItemRepository restockItemRepository;
    private final BusinessRepository businessRepository;
    private final BranchRepository branchRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierContactRepository supplierContactRepository;
    private final ItemRepository itemRepository;
    private final PathAPurchaseService pathAPurchaseService;

    @Transactional(readOnly = true)
    public byte[] buildOrderPdf(String businessId, String orderNumber) {
        return RestockOrderPdfRenderer.render(loadSnapshot(businessId, orderNumber, null));
    }

    @Transactional
    public ConvertRestockOrderResponse convertToPathAPurchaseOrder(
            String businessId,
            String orderNumber,
            String userId,
            boolean sendPurchaseOrder,
            String adminNotes
    ) {
        List<StockTakeRestockItem> rows = requireDraftOrderRows(businessId, orderNumber);
        if (rows.stream().anyMatch(r -> r.getPurchaseOrderId() != null && !r.getPurchaseOrderId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already linked to a purchase order");
        }

        StockTakeRestockItem first = rows.getFirst();
        String supplierId = first.getSupplierId();
        String branchId = first.getBranchId();
        if (rows.stream().anyMatch(r -> !supplierId.equals(r.getSupplierId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order spans multiple suppliers");
        }
        if (rows.stream().anyMatch(r -> !branchId.equals(r.getBranchId()))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order spans multiple branches");
        }
        for (StockTakeRestockItem row : rows) {
            if (row.getBuyingPrice() == null || row.getBuyingPrice().signum() <= 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "All lines require a positive buying price");
            }
        }

        String notes = combineNotes(adminNotes, orderNumber);
        CreatePathAPurchaseOrderRequest createReq =
                new CreatePathAPurchaseOrderRequest(
                        supplierId, branchId, null, orderNumber, notes);
        PathAPurchaseOrderDetailResponse po =
                pathAPurchaseService.createPurchaseOrder(businessId, createReq);

        for (StockTakeRestockItem row : rows) {
            pathAPurchaseService.addPurchaseOrderLine(
                    businessId,
                    po.id(),
                    new AddPathAPurchaseOrderLineRequest(
                            row.getItemId(),
                            row.getSuggestedQty(),
                            row.getBuyingPrice()));
        }

        PathAPurchaseOrderDetailResponse finalPo = po;
        if (sendPurchaseOrder) {
            finalPo = pathAPurchaseService.sendPurchaseOrder(businessId, po.id());
        }

        Instant now = Instant.now();
        for (StockTakeRestockItem row : rows) {
            row.setPurchaseOrderId(finalPo.id());
            if (sendPurchaseOrder) {
                row.setStatus(InventoryConstants.RESTOCK_STATUS_ORDERED);
                row.setReviewedBy(userId);
                row.setReviewedAt(now);
            }
            restockItemRepository.save(row);
        }

        return new ConvertRestockOrderResponse(
                orderNumber,
                finalPo.id(),
                finalPo.poNumber(),
                sendPurchaseOrder
                        ? InventoryConstants.RESTOCK_STATUS_ORDERED
                        : InventoryConstants.RESTOCK_STATUS_ORDER_DRAFTED);
    }

    RestockOrderSnapshot loadSnapshot(String businessId, String orderNumber, String adminNotes) {
        List<StockTakeRestockItem> rows = requireOrderRows(businessId, orderNumber);
        StockTakeRestockItem first = rows.getFirst();

        Business business =
                businessRepository
                        .findById(businessId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        Branch branch =
                branchRepository
                        .findByIdAndBusinessIdAndDeletedAtIsNull(first.getBranchId(), businessId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Branch not found"));
        Supplier supplier =
                supplierRepository
                        .findByIdAndBusinessIdAndDeletedAtIsNull(first.getSupplierId(), businessId)
                        .orElseThrow(
                                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Supplier not found"));

        SupplierContact contact =
                supplierContactRepository
                        .findBySupplierIdOrderByPrimaryContactDescNameAsc(first.getSupplierId())
                        .stream()
                        .findFirst()
                        .orElse(null);

        Map<String, Item> items =
                itemRepository.findAllById(
                                rows.stream().map(StockTakeRestockItem::getItemId).distinct().toList())
                        .stream()
                        .collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));

        List<RestockOrderLineRow> lineRows = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (StockTakeRestockItem row : rows) {
            Item item = items.get(row.getItemId());
            BigDecimal lineTotal = lineTotal(row.getSuggestedQty(), row.getBuyingPrice());
            if (lineTotal != null) {
                subtotal = subtotal.add(lineTotal);
            }
            lineRows.add(
                    new RestockOrderLineRow(
                            item != null ? item.getName() : "",
                            item != null ? item.getSku() : null,
                            row.getSuggestedQty(),
                            packLabel(row.getSupplierPackSize(), row.getSupplierPackUnit()),
                            row.getBuyingPrice(),
                            lineTotal,
                            row.getNote()));
        }

        Instant draftedAt = first.getOrderDraftedAt() != null ? first.getOrderDraftedAt() : Instant.now();
        return new RestockOrderSnapshot(
                business.getName(),
                branch.getName(),
                orderNumber,
                DATE_FMT.format(draftedAt),
                supplier.getName(),
                contact != null ? contact.getPhone() : null,
                contact != null ? contact.getEmail() : null,
                adminNotes,
                lineRows,
                subtotal.setScale(2, RoundingMode.HALF_UP));
    }

    private List<StockTakeRestockItem> requireDraftOrderRows(String businessId, String orderNumber) {
        List<StockTakeRestockItem> rows = requireOrderRows(businessId, orderNumber);
        if (rows.stream().anyMatch(r -> !InventoryConstants.RESTOCK_STATUS_ORDER_DRAFTED.equals(r.getStatus()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only drafted orders can be converted");
        }
        return rows;
    }

    private List<StockTakeRestockItem> requireOrderRows(String businessId, String orderNumber) {
        List<StockTakeRestockItem> rows =
                restockItemRepository.findByBusinessIdAndOrderNumber(businessId, orderNumber);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        }
        return rows;
    }

    private static String combineNotes(String adminNotes, String orderNumber) {
        String prefix = "Converted from daily audit restock order " + orderNumber;
        if (adminNotes == null || adminNotes.isBlank()) {
            return prefix;
        }
        return prefix + "\n" + adminNotes.trim();
    }

    private static String packLabel(BigDecimal packSize, String packUnit) {
        if (packSize != null && packUnit != null && !packUnit.isBlank()) {
            return packSize.stripTrailingZeros().toPlainString() + " " + packUnit.trim();
        }
        if (packUnit != null && !packUnit.isBlank()) {
            return packUnit.trim();
        }
        return "—";
    }

    private static BigDecimal lineTotal(BigDecimal qty, BigDecimal price) {
        if (qty == null || price == null) {
            return null;
        }
        return qty.multiply(price).setScale(4, RoundingMode.HALF_UP);
    }
}
