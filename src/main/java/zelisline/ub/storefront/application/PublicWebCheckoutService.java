package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.sales.SalesConstants;
import zelisline.ub.storefront.WebOrderStatuses;
import zelisline.ub.storefront.api.dto.PublicCheckoutRequest;
import zelisline.ub.storefront.api.dto.PublicCheckoutResponse;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.storefront.repository.WebOrderLineRepository;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;
import zelisline.ub.tenancy.application.StorefrontSettingsService;

@Service
public class PublicWebCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PublicWebCheckoutService.class);

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final int QTY_SCALE = 4;

    private final PublicWebCartService publicWebCartService;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final ItemRepository itemRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderLineRepository webOrderLineRepository;
    private final WebCartRepository webCartRepository;
    private final OrderConfirmationEmailRenderer orderConfirmationEmailRenderer;
    private final NotificationService notificationService;
    private final StorefrontSettingsService storefrontSettingsService;
    private final zelisline.ub.notifications.application.NotificationOutboxService notificationOutboxService;

    public PublicWebCheckoutService(
            PublicWebCartService publicWebCartService,
            InventoryBatchPickerService inventoryBatchPickerService,
            PackageVariantStockResolver packageVariantStockResolver,
            ItemRepository itemRepository,
            InventoryBatchRepository inventoryBatchRepository,
            WebOrderRepository webOrderRepository,
            WebOrderLineRepository webOrderLineRepository,
            WebCartRepository webCartRepository,
            OrderConfirmationEmailRenderer orderConfirmationEmailRenderer,
            NotificationService notificationService,
            StorefrontSettingsService storefrontSettingsService,
            zelisline.ub.notifications.application.NotificationOutboxService notificationOutboxService
    ) {
        this.publicWebCartService = publicWebCartService;
        this.inventoryBatchPickerService = inventoryBatchPickerService;
        this.packageVariantStockResolver = packageVariantStockResolver;
        this.itemRepository = itemRepository;
        this.inventoryBatchRepository = inventoryBatchRepository;
        this.webOrderRepository = webOrderRepository;
        this.webOrderLineRepository = webOrderLineRepository;
        this.webCartRepository = webCartRepository;
        this.orderConfirmationEmailRenderer = orderConfirmationEmailRenderer;
        this.notificationService = notificationService;
        this.storefrontSettingsService = storefrontSettingsService;
        this.notificationOutboxService = notificationOutboxService;
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
            if (itemRow.isSellable()) {
                Item stockHolder = packageVariantStockResolver.requireInventoryHolder(
                        businessId, itemRow.getId());
                reconcileCurrentStockFromBatches(stockHolder, businessId, branchId);
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

        // ── send order confirmation email (best-effort, must not roll back the order) ──
        String customerEmail = order.getCustomerEmail();
        if (customerEmail != null && !customerEmail.isBlank()) {
            try {
                List<WebOrderLine> orderLines =
                        webOrderLineRepository.findByOrderIdOrderByLineIndexAsc(order.getId());
                String branchName = elig.ctx().catalogBranch().getName();
                var business = elig.ctx().business();
                TenantBrandingDto branding = storefrontSettingsService
                        .readTenantConfig(business.getSettings(), business.getName())
                        .branding();
                String storeName = OrderConfirmationEmailRenderer.resolveStoreName(
                        branding, business.getName(), business.getSlug(), branchName);
                String brand = OrderConfirmationEmailRenderer.brandWordmark(storeName);
                String htmlBody = orderConfirmationEmailRenderer.renderHtml(
                        order, orderLines, branchName, branding, business.getName(), business.getSlug());
                String subject = "Order confirmed \u2014 " + brand;
                notificationService.sendOrderConfirmationHtml(
                        customerEmail, subject, htmlBody, brand);
            } catch (Exception e) {
                log.warn("Failed to send order confirmation email for order {} to {}: {}",
                        order.getId(), customerEmail, e.getMessage());
            }
        }

        try {
            notificationOutboxService.enqueueWebOrderPlaced(order);
        } catch (Exception e) {
            log.warn("Failed to create notifications for order {}: {}", order.getId(), e.getMessage());
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
