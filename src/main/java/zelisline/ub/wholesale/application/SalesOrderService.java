package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.PackageVariantStockResolver;
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.inventory.InventoryConstants;
import zelisline.ub.inventory.application.InventoryBatchPickerService;
import zelisline.ub.pharmacy.application.DispensingGate;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.application.PublicWebCartService;
import zelisline.ub.storefront.application.PublicStorefrontContextService;
import zelisline.ub.storefront.application.PublicWebCheckoutService;
import zelisline.ub.storefront.application.StorefrontOnlinePurchaseRules;
import zelisline.ub.storefront.application.WhatsAppOrderExpiryService;
import zelisline.ub.storefront.repository.WebCartRepository;
import zelisline.ub.tenancy.api.dto.StoreTypeCodes;
import zelisline.ub.tenancy.application.TenancyService;
import zelisline.ub.wholesale.api.dto.AnswerSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.PlaceSalesOrderRequest;
import zelisline.ub.wholesale.api.dto.SalesOrderLineResponse;
import zelisline.ub.wholesale.api.dto.SalesOrderPlacedResponse;
import zelisline.ub.wholesale.api.dto.SalesOrderResponse;
import zelisline.ub.wholesale.api.dto.SalesOrderTrackingResponse;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.domain.TradeAccount;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

/**
 * Trade orders placed with a supplier's shop (ADR 0040).
 *
 * <p>Placing one turns the storefront cart into a {@code sales_order} in the supplier's tenant. It
 * differs from web checkout on purpose: no stock is taken, nothing is paid, and the order does not
 * expire. The supplier answers it. Only a supplier's shop takes them.
 *
 * <p>Answering says, line by line, how much the supplier will supply. Stock leaves the shelf then,
 * the way it leaves at web checkout, and goes back if the order is cancelled before dispatch.
 */
@Service
@RequiredArgsConstructor
public class SalesOrderService {

    /** Orders one phone number may have waiting for an answer at one shop. Nothing is paid or held, so cap it. */
    static final int MAX_OPEN_PER_BUYER = 5;
    private static final int LIST_LIMIT = 200;
    private static final int QTY_SCALE = 4;
    private static final int MONEY_SCALE = 2;

    private final PublicWebCartService publicWebCartService;
    private final WebCartRepository webCartRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final ItemRepository itemRepository;
    private final TenancyService tenancyService;
    private final DispensingGate dispensingGate;
    private final PublicWebCheckoutService checkoutService;
    private final PackageVariantStockResolver packageVariantStockResolver;
    private final InventoryBatchPickerService inventoryBatchPickerService;
    private final WhatsAppOrderExpiryService stockReleaseService;
    private final PublicStorefrontContextService storefrontContextService;
    private final TradeAccountService tradeAccountService;
    private final zelisline.ub.trade.application.TradeOrderService tradeOrderService;
    private final SalesInvoiceService salesInvoiceService;
    private final zelisline.ub.wholesale.repository.SalesInvoiceRepository salesInvoiceRepository;
    private final SupplierMilestoneService supplierMilestoneService;

    @Transactional
    public SalesOrderPlacedResponse placeFromCart(String slug, String cartId, PlaceSalesOrderRequest req) {
        return placeFromCart(slug, cartId, req, null);
    }

    /**
     * As above, for a signed-in marketplace buyer: {@code verifiedBuyerBusinessId} is the shop the
     * buyer is staff of (ADR 0043). The caller has already checked that; this method trusts it.
     */
    @Transactional
    public SalesOrderPlacedResponse placeFromCart(
            String slug, String cartId, PlaceSalesOrderRequest req, String verifiedBuyerBusinessId) {
        // Same checks as checkout: every line still sold online, priced, in stock, at or above its minimum.
        PublicWebCartService.CheckoutEligibility cart =
                publicWebCartService.requireCheckoutEligible(slug, cartId.trim());
        String businessId = cart.ctx().business().getId();
        if (!StoreTypeCodes.isSupplierBusiness(tenancyService.currentStoreTypesForTenant(businessId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This shop does not take trade orders");
        }
        String name = req.buyerName().trim();
        String phone = req.buyerPhone().trim();
        if (name.isEmpty() || phone.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A name and a phone number are required");
        }
        // Counted by the number in one spelling, so writing it another way does not get round the cap.
        String phoneKey = TradeAccountService.phoneKey(phone);
        long waiting = phoneKey != null
                ? salesOrderRepository.countByBusinessIdAndBuyerPhoneKeyAndStatus(
                        businessId, phoneKey, SalesOrderStatuses.PLACED)
                : salesOrderRepository.countByBusinessIdAndBuyerPhoneAndStatus(
                        businessId, phone, SalesOrderStatuses.PLACED);
        if (waiting >= MAX_OPEN_PER_BUYER) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "You already have " + MAX_OPEN_PER_BUYER + " orders waiting for this shop to answer");
        }
        // Medicines keep their rules whoever is buying (ADR 0033).
        dispensingGate.assertOnlineSellable(
                businessId, cart.lines().stream().map(l -> l.item().getId()).toList());

        SalesOrder order = new SalesOrder();
        order.setBusinessId(businessId);
        order.setBranchId(cart.cart().getCatalogBranchId());
        order.setCurrency(cart.ctx().business().getCurrency());
        order.setTotal(cart.grandTotal());
        order.setBuyerName(name);
        order.setBuyerPhone(phone);
        order.setBuyerEmail(blankToNull(req.buyerEmail()));
        order.setBuyerBusinessName(blankToNull(req.buyerBusinessName()));
        order.setNotes(blankToNull(req.notes()));
        order.setBuyerPhoneKey(phoneKey);
        order.setBuyerBusinessId(verifiedBuyerBusinessId);
        // An approved account makes this an order on credit; everyone else pays before dispatch.
        // A shop's own account comes first; a phone number's account is the fallback.
        TradeAccount account = tradeAccountService.approvedForShop(businessId, verifiedBuyerBusinessId)
                .or(() -> tradeAccountService.approvedFor(businessId, phone))
                .orElse(null);
        // The trade discount and the account's own prices are for a shop whose staff signed in,
        // never for a typed phone number.
        boolean shopsOwnAccount = account != null
                && verifiedBuyerBusinessId != null
                && verifiedBuyerBusinessId.equals(account.getBuyerBusinessId());
        BigDecimal discount = shopsOwnAccount
                && account.getDiscountPercent() != null
                && account.getDiscountPercent().signum() > 0
                ? account.getDiscountPercent()
                : null;
        Map<String, BigDecimal> ownPrices = shopsOwnAccount
                ? tradeAccountService.priceList(businessId, account.getId())
                : Map.of();
        BigDecimal total = BigDecimal.ZERO;
        List<SalesOrderLine> rows = new java.util.ArrayList<>();
        for (PublicWebCartService.CheckoutLine line : cart.lines()) {
            BigDecimal unitPrice = line.unitPrice();
            BigDecimal lineTotal = line.lineTotal();
            BigDecimal ownPrice = ownPrices.get(line.item().getId());
            if (ownPrice != null) {
                // A price agreed for this buyer and this product is the price; no discount on top.
                unitPrice = ownPrice;
                lineTotal = unitPrice.multiply(line.quantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            } else if (discount != null) {
                unitPrice = unitPrice
                        .multiply(BigDecimal.valueOf(100).subtract(discount))
                        .divide(BigDecimal.valueOf(100), MONEY_SCALE, RoundingMode.HALF_UP);
                lineTotal = unitPrice.multiply(line.quantity()).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
            }
            SalesOrderLine row = new SalesOrderLine();
            row.setBusinessId(businessId);
            row.setItemId(line.item().getId());
            row.setItemName(displayName(businessId, line.item()));
            row.setQuantity(line.quantity());
            row.setUnitPrice(unitPrice);
            row.setLineTotal(lineTotal);
            row.setLineIndex(line.lineIndex());
            rows.add(row);
            total = total.add(lineTotal);
        }
        order.setTotal(total);
        if (account != null) {
            order.setPaymentTerms(SalesOrderStatuses.TERMS_CREDIT);
            order.setTradeAccountId(account.getId());
            if (account.getCreditLimit() != null) {
                BigDecimal owedOnInvoices = salesInvoiceRepository.sumOwedForAccount(businessId, account.getId());
                BigDecimal notInvoicedYet = salesOrderRepository.sumOpenOrdersForAccount(businessId, account.getId());
                BigDecimal already = (owedOnInvoices == null ? BigDecimal.ZERO : owedOnInvoices)
                        .add(notInvoicedYet == null ? BigDecimal.ZERO : notInvoicedYet);
                order.setOverCreditLimit(already.add(total).compareTo(account.getCreditLimit()) > 0);
            }
        }
        salesOrderRepository.save(order);
        for (SalesOrderLine row : rows) {
            row.setOrderId(order.getId());
            salesOrderLineRepository.save(row);
        }

        webCartRepository.deleteById(cart.cart().getId());

        return new SalesOrderPlacedResponse(
                order.getId(),
                WebOrderCodes.code(order.getId()),
                order.getStatus(),
                order.getTotal(),
                order.getCurrency(),
                order.getCreatedAt());
    }

    /**
     * Opens or updates the account of the shop a verified order came from: approve it with a
     * limit, days to pay and a trade discount, or decline or suspend it (P3-4).
     */
    @Transactional
    public zelisline.ub.wholesale.api.dto.TradeAccountResponse decideAccountForOrder(
            String businessId, String orderId, zelisline.ub.wholesale.api.dto.DecideTradeAccountRequest req) {
        SalesOrder order = requireOrder(businessId, orderId);
        return tradeAccountService.decideForVerifiedBuyer(
                businessId,
                order.getBuyerBusinessId(),
                order.getBuyerPhone(),
                order.getBuyerName(),
                order.getBuyerBusinessName(),
                req);
    }

    /** The shop's trade orders, newest first; all of them, or those in one status. */
    @Transactional(readOnly = true)
    public List<SalesOrderResponse> list(String businessId, String status) {
        PageRequest page = PageRequest.of(0, LIST_LIMIT);
        var rows = status == null || status.isBlank()
                ? salesOrderRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, page)
                : salesOrderRepository.findByBusinessIdAndStatusOrderByCreatedAtDesc(
                        businessId, status.trim().toLowerCase(java.util.Locale.ROOT), page);
        return rows.getContent().stream().map(o -> toResponse(o, null)).toList();
    }

    @Transactional(readOnly = true)
    public SalesOrderResponse get(String businessId, String orderId) {
        SalesOrder order = salesOrderRepository.findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        List<SalesOrderLineResponse> lines = salesOrderLineRepository
                .findByOrderIdAndBusinessIdOrderByLineIndexAsc(order.getId(), businessId)
                .stream()
                .map(l -> new SalesOrderLineResponse(
                        l.getId(), l.getItemId(), l.getItemName(), l.getQuantity(), l.getUnitPrice(),
                        l.getLineTotal(), l.getStatus(), l.getAcceptedQty()))
                .toList();
        return toResponse(order, lines);
    }

    /**
     * The buyer's view of their order, opened with the id they were given when they placed it. The
     * id is long and random, so it serves as the key; an id from another shop is simply not found.
     */
    @Transactional(readOnly = true)
    public SalesOrderTrackingResponse track(String slug, String orderId) {
        String businessId = storefrontContextService.requireForSlug(slug).business().getId();
        SalesOrder order = requireOrder(businessId, orderId.trim());
        List<SalesOrderTrackingResponse.Line> lines = salesOrderLineRepository
                .findByOrderIdAndBusinessIdOrderByLineIndexAsc(order.getId(), businessId)
                .stream()
                .map(l -> new SalesOrderTrackingResponse.Line(
                        l.getItemName(), l.getQuantity(), l.getAcceptedQty(), l.getUnitPrice(),
                        l.getLineTotal(), l.getStatus()))
                .toList();
        return new SalesOrderTrackingResponse(
                order.getId(),
                WebOrderCodes.code(order.getId()),
                order.getStatus(),
                order.getCurrency(),
                order.getTotal(),
                order.getCreatedAt(),
                lines);
    }

    /**
     * The supplier's answer to a placed order. Every line gets a quantity: all of it, some of it,
     * or none. Stock for what is accepted is taken now; if any line cannot be supplied from stock
     * the whole answer is refused and nothing changes.
     */
    @Transactional
    public SalesOrderResponse answer(String businessId, String orderId, AnswerSalesOrderRequest req) {
        SalesOrder order = requireOrder(businessId, orderId);
        if (!SalesOrderStatuses.PLACED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has already been answered");
        }
        Map<String, BigDecimal> answers = new HashMap<>();
        for (AnswerSalesOrderRequest.Line line : req.lines()) {
            if (answers.put(line.lineId().trim(), line.acceptedQty()) != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A line was answered twice");
            }
        }
        List<SalesOrderLine> lines =
                salesOrderLineRepository.findByOrderIdAndBusinessIdOrderByLineIndexAsc(order.getId(), businessId);
        if (answers.size() != lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every line needs an answer");
        }

        BigDecimal total = BigDecimal.ZERO;
        int supplied = 0;
        int inFull = 0;
        for (SalesOrderLine line : lines) {
            BigDecimal raw = answers.get(line.getId());
            if (raw == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Every line needs an answer");
            }
            BigDecimal qty = raw.setScale(QTY_SCALE, RoundingMode.HALF_UP);
            if (qty.compareTo(line.getQuantity()) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "You cannot supply more of " + line.getItemName() + " than was ordered");
            }
            if (qty.signum() == 0) {
                line.setStatus(SalesOrderStatuses.LINE_REJECTED);
                line.setAcceptedQty(BigDecimal.ZERO.setScale(QTY_SCALE));
                line.setLineTotal(BigDecimal.ZERO.setScale(MONEY_SCALE));
            } else {
                Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(line.getItemId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, line.getItemName() + " is no longer in your catalogue"));
                StorefrontOnlinePurchaseRules.requireValidQuantity(item, qty);
                takeStock(order, item, qty);
                BigDecimal lineTotal = line.getUnitPrice().multiply(qty).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
                line.setStatus(SalesOrderStatuses.LINE_ACCEPTED);
                line.setAcceptedQty(qty);
                line.setLineTotal(lineTotal);
                total = total.add(lineTotal);
                supplied++;
                if (qty.compareTo(line.getQuantity()) == 0) {
                    inFull++;
                }
            }
            salesOrderLineRepository.save(line);
        }

        order.setAnsweredAt(java.time.Instant.now());
        order.setStatus(supplied == 0
                ? SalesOrderStatuses.REJECTED
                : inFull == lines.size() ? SalesOrderStatuses.ACCEPTED : SalesOrderStatuses.PART_ACCEPTED);
        // From here the total is what the buyer owes: what was accepted, at the prices placed.
        order.setTotal(total);
        salesOrderRepository.save(order);
        // A signed-in marketplace buyer ordered this for their shop: if that shop is connected,
        // the order crosses to it now, already confirmed, with nobody having to vouch for it.
        // No engagement call here: milestones are recorded when the supplier's overview is read
        // (SupplierMilestoneService.recordDue), so answering an order costs nothing extra.
        if (supplied > 0 && order.getBuyerBusinessId() != null) {
            tradeOrderService.openForVerifiedBuyer(businessId, order.getId(), order.getBuyerBusinessId());
        }
        return get(businessId, order.getId());
    }

    /** The accepted goods have left. After this the order cannot be cancelled here. */
    @Transactional
    public SalesOrderResponse dispatch(String businessId, String orderId) {
        SalesOrder order = requireOrder(businessId, orderId);
        if (!holdsStock(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Only an accepted order can be dispatched");
        }
        order.setDispatchedAt(java.time.Instant.now());
        order.setStatus(SalesOrderStatuses.DISPATCHED);
        salesOrderRepository.save(order);
        // If this order was sent to a connected shop, that shop sees it is on its way.
        tradeOrderService.onSalesOrderDispatched(businessId, order.getId());
        // The goods have left, so the buyer owes for them: the invoice and its ledger entry (ADR 0042).
        salesInvoiceService.issueOnDispatch(order);
        return get(businessId, order.getId());
    }

    /** Calls the order off before dispatch. Stock an accepted order took goes back on the shelf. */
    @Transactional
    public SalesOrderResponse cancel(String businessId, String orderId) {
        SalesOrder order = requireOrder(businessId, orderId);
        boolean placed = SalesOrderStatuses.PLACED.equals(order.getStatus());
        if (!placed && !holdsStock(order)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "This order can no longer be cancelled");
        }
        if (!placed) {
            stockReleaseService.releaseOutstandingStock(
                    businessId,
                    order.getBranchId(),
                    SalesOrderStatuses.STOCK_REFERENCE_TYPE,
                    order.getId(),
                    InventoryConstants.MOVEMENT_SALE_VOID,
                    "Trade order cancelled, stock put back");
        }
        order.setCancelledFrom(order.getStatus());
        order.setStatus(SalesOrderStatuses.CANCELLED);
        salesOrderRepository.save(order);
        tradeOrderService.onSalesOrderCancelled(businessId, order.getId());
        return get(businessId, order.getId());
    }

    private static boolean holdsStock(SalesOrder order) {
        return SalesOrderStatuses.ACCEPTED.equals(order.getStatus())
                || SalesOrderStatuses.PART_ACCEPTED.equals(order.getStatus());
    }

    private SalesOrder requireOrder(String businessId, String orderId) {
        return salesOrderRepository.findByIdAndBusinessId(orderId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    /** As web checkout takes it: from the product that holds the stock, oldest batch first. */
    private void takeStock(SalesOrder order, Item item, BigDecimal quantity) {
        if (!item.isSellable()) {
            return;
        }
        String businessId = order.getBusinessId();
        Item holder = packageVariantStockResolver.requireInventoryHolder(businessId, item.getId());
        checkoutService.reconcileCurrentStockFromBatches(holder, businessId, order.getBranchId());
        inventoryBatchPickerService.pickAndApplyPhysicalDecrement(
                businessId,
                item.getId(),
                order.getBranchId(),
                quantity,
                SalesOrderStatuses.STOCK_REFERENCE_TYPE,
                order.getId(),
                InventoryConstants.MOVEMENT_SALE,
                null);
    }

    private static SalesOrderResponse toResponse(SalesOrder order, List<SalesOrderLineResponse> lines) {
        return new SalesOrderResponse(
                order.getId(),
                WebOrderCodes.code(order.getId()),
                order.getStatus(),
                order.getCurrency(),
                order.getTotal(),
                order.getBuyerName(),
                order.getBuyerPhone(),
                order.getBuyerEmail(),
                order.getBuyerBusinessName(),
                order.getNotes(),
                order.getCreatedAt(),
                lines,
                order.getPaymentTerms(),
                order.getBuyerBusinessId() != null,
                order.isOverCreditLimit());
    }

    /** "Soda 500ml Carton of 12": a pack is named with the product it is a pack of. */
    private String displayName(String businessId, Item item) {
        String parentName = null;
        String parentId = item.getVariantOfItemId();
        if (parentId != null && !parentId.isBlank()) {
            parentName = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, businessId)
                    .map(Item::getName)
                    .orElse(null);
        }
        String composed = ProductDisplayName.forVariant(item, parentName);
        if (!composed.isBlank()) {
            return composed;
        }
        return item.getName() != null && !item.getName().isBlank() ? item.getName() : "Item";
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
