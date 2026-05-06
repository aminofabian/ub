package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.SalesConstants;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.PublicCheckoutRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;

@Service
public class PublicWebCheckoutService {

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int QTY_SCALE = 4;

    private final PublicWebCartService publicWebCartService;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final WebCartRepository webCartRepository;

    public PublicWebCheckoutService(
            PublicWebCartService publicWebCartService,
            InventoryBatchPickerService inventoryBatchPickerService,
            ItemRepository itemRepository,
            InventoryBatchRepository inventoryBatchRepository,
            WebOrderRepository webOrderRepository,
            WebOrderLineRepository webOrderLineRepository,
            WebCartRepository webCartRepository
    ) {
        this.publicWebCartService = publicWebCartService;
        this.inventoryBatchPickerService = inventoryBatchPickerService;
        this.itemRepository = itemRepository;
        this.inventoryBatchRepository = inventoryBatchRepository;
        this.webOrderRepository = webOrderRepository;
        this.webOrderLineRepository = webOrderLineRepository;
        this.webCartRepository = webCartRepository;
    }

    @Transactional
    public PublicCheckoutResponse submitCheckout(String slug, String cartId, PublicCheckoutRequest req) {
        PublicWebCartService.CheckoutEligibility elig =
                publicWebCartService.requireCheckoutEligible(slug, cartId.trim());
        String name = req.customerName().trim();
        String phone = req.customerPhone().trim();
        if (name.isEmpty() || phone.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contact details required");
        }
        String email = normalizeOptionalEmail(req.customerEmail());
        String notes = blankToNull(req.notes());

        WebOrder order = new WebOrder();
        order.setBusinessId(elig.ctx().business().getId());
        order.setCartId(elig.cart().getId());
        order.setCatalogBranchId(elig.cart().getCatalogBranchId());
        order.setStatus(WebOrderStatuses.PENDING_PAYMENT);
        order.setCurrency(elig.ctx().business().getCurrency());
        order.setGrandTotal(elig.grandTotal());
        order.setCustomerName(name);
        order.setCustomerPhone(phone);
        order.setCustomerEmail(email);
        order.setNotes(notes);
        webOrderRepository.save(order);

        String businessId = elig.ctx().business().getId();
        String branchId = elig.ctx().catalogBranch().getId();
        for (PublicWebCartService.CheckoutLine line : elig.lines()) {
            Item itemRow = itemRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(line.item().getId(), businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
            if (itemRow.isStocked()) {
                reconcileCurrentStockFromBatches(itemRow, businessId, branchId);
                inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                        businessId,
                        itemRow.getId(),
                        branchId,
                        line.quantity(),
                        SalesConstants.STOCK_REFERENCE_TYPE_WEB_ORDER,
                        order.getId(),
                        InventoryConstants.MOVEMENT_SALE,
                        null);
            }
        }

        for (PublicWebCartService.CheckoutLine line : elig.lines()) {
            WebOrderLine ol = new WebOrderLine();
            ol.setOrderId(order.getId());
            ol.setItemId(line.item().getId());
            ol.setItemName(line.item().getName());
            ol.setVariantName(blankToNull(line.item().getVariantName()));
            ol.setQuantity(line.quantity());
            ol.setUnitPrice(line.unitPrice());
            ol.setLineTotal(line.lineTotal());
            ol.setLineIndex(line.lineIndex());
            webOrderLineRepository.save(ol);
        }

        webCartRepository.deleteById(elig.cart().getId());

        return new PublicCheckoutResponse(
                order.getId(),
                order.getStatus(),
                order.getGrandTotal(),
                order.getCurrency(),
                elig.ctx().catalogBranch().getName(),
                order.getCreatedAt());
    }

    /**
     * Align {@link Item#getCurrentStock()} with summed batch quantities so the batch picker's aggregate
     * adjustment matches physical FIFO/FEFO allocations (avoids failure when batches were updated without
     * refreshing the item-level counter).
     */
    private void reconcileCurrentStockFromBatches(Item item, String businessId, String branchId) {
        List<Object[]> rows = inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId,
                branchId,
                InventoryConstants.BATCH_STATUS_ACTIVE,
                List.of(item.getId()));
        BigDecimal sum = BigDecimal.ZERO;
        if (!rows.isEmpty() && rows.getFirst()[1] instanceof BigDecimal bd) {
            sum = bd;
        }
        item.setCurrentStock(sum.setScale(QTY_SCALE, RoundingMode.HALF_UP));
        itemRepository.save(item);
    }

    private static String normalizeOptionalEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String e = raw.trim();
        if (!EMAIL.matcher(e).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid email");
        }
        return e;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
