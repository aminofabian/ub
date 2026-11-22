package zelisline.ub.trade.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.marketplace.domain.BusinessSupplierConnection;
import zelisline.ub.marketplace.domain.BusinessSupplierConnectionStatuses;
import zelisline.ub.marketplace.domain.MarketplaceSupplier;
import zelisline.ub.marketplace.repository.BusinessSupplierConnectionRepository;
import zelisline.ub.marketplace.repository.MarketplaceSupplierRepository;
import zelisline.ub.purchasing.PurchasingConstants;
import zelisline.ub.purchasing.api.dto.AddPathAPurchaseOrderLineRequest;
import zelisline.ub.purchasing.api.dto.CreatePathAPurchaseOrderRequest;
import zelisline.ub.purchasing.application.PathAPurchaseService;
import zelisline.ub.purchasing.domain.PurchaseOrder;
import zelisline.ub.purchasing.repository.PurchaseOrderRepository;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;
import zelisline.ub.trade.api.dto.ConnectedShopResponse;
import zelisline.ub.trade.api.dto.MakePurchaseOrderRequest;
import zelisline.ub.trade.api.dto.RecordReceivedRequest;
import zelisline.ub.trade.api.dto.TradeOrderMatchResponse;
import zelisline.ub.trade.api.dto.TradeOrderPurchaseOrderResponse;
import zelisline.ub.trade.api.dto.TradeOrderResponse;
import zelisline.ub.trade.domain.TradeOrder;
import zelisline.ub.trade.domain.TradeOrderEvent;
import zelisline.ub.trade.domain.TradeOrderLine;
import zelisline.ub.trade.repository.TradeOrderEventRepository;
import zelisline.ub.trade.repository.TradeOrderLineRepository;
import zelisline.ub.trade.repository.TradeOrderRepository;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderLine;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.repository.SalesOrderLineRepository;
import zelisline.ub.wholesale.repository.SalesOrderRepository;

/**
 * The trade lane (ADR 0041): the one place a supplier's shop and a buyer's shop meet.
 *
 * <p>A supplier sends an order it has accepted to a shop it is connected to. That writes a
 * {@link TradeOrder}: products, packs, quantities and prices, nothing else. The buyer's shop reads
 * it and confirms or declines. Neither side reads or writes the other's books here, and the
 * buyer's purchase order is the buyer's to make.
 *
 * <p>Every method takes the caller's own business id and checks the order against it. A supplier
 * can only send to a shop that has an ACTIVE connection to its passport; a connection is only ever
 * made active by the shop.
 */
@Service
@RequiredArgsConstructor
public class TradeOrderService {

    /** Orders one supplier may have waiting for one shop's answer. The shop did not ask for them. */
    static final int MAX_WAITING_PER_SHOP = 5;

    private final TradeOrderRepository tradeOrderRepository;
    private final TradeOrderLineRepository tradeOrderLineRepository;
    private final TradeOrderEventRepository tradeOrderEventRepository;
    private final SalesOrderRepository salesOrderRepository;
    private final SalesOrderLineRepository salesOrderLineRepository;
    private final MarketplaceSupplierRepository marketplaceSupplierRepository;
    private final BusinessSupplierConnectionRepository connectionRepository;
    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final PathAPurchaseService pathAPurchaseService;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final BranchResolutionService branchResolutionService;
    private final zelisline.ub.marketplace.repository.MarketplaceEscrowHoldRepository escrowHoldRepository;
    private final zelisline.ub.marketplace.repository.SupplierPerformanceEventRepository performanceEventRepository;

    // ── The supplier's side ────────────────────────────────────────────────────

    /** Shops that have an active connection to this supplier's passport. Empty for any other shop. */
    @Transactional(readOnly = true)
    public List<ConnectedShopResponse> connectedShops(String supplierBusinessId) {
        Optional<MarketplaceSupplier> passport = marketplaceSupplierRepository.findFirstByBusinessId(supplierBusinessId);
        if (passport.isEmpty()) {
            return List.of();
        }
        List<String> shopIds = connectionRepository
                .findByMarketplaceSupplierIdAndStatus(passport.get().getId(), BusinessSupplierConnectionStatuses.ACTIVE)
                .stream()
                .map(BusinessSupplierConnection::getBusinessId)
                .filter(id -> !id.equals(supplierBusinessId))
                .distinct()
                .toList();
        List<ConnectedShopResponse> out = new ArrayList<>();
        for (Business shop : businessRepository.findAllById(shopIds)) {
            out.add(new ConnectedShopResponse(shop.getId(), shop.getName()));
        }
        out.sort((a, b) -> String.valueOf(a.name()).compareToIgnoreCase(String.valueOf(b.name())));
        return out;
    }

    /**
     * Sends an accepted order to a connected shop. Only what the supplier accepted goes across,
     * at the prices the order was placed at. Sending the same order twice returns what was sent.
     */
    @Transactional
    public TradeOrderResponse sendToShop(String supplierBusinessId, String salesOrderId, String buyerBusinessId) {
        SalesOrder order = salesOrderRepository.findByIdAndBusinessId(salesOrderId, supplierBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
        String buyerId = buyerBusinessId.trim();
        Optional<TradeOrder> already = tradeOrderRepository.findBySalesOrderId(order.getId());
        if (already.isPresent()) {
            if (!already.get().getBuyerBusinessId().equals(buyerId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "This order was already sent to another shop");
            }
            return toResponse(already.get(), true);
        }
        if (!SalesOrderStatuses.ACCEPTED.equals(order.getStatus())
                && !SalesOrderStatuses.PART_ACCEPTED.equals(order.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Accept the order before sending it to a shop");
        }
        MarketplaceSupplier passport = marketplaceSupplierRepository.findFirstByBusinessId(supplierBusinessId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "Only a supplier's shop can send orders to other shops"));
        if (buyerId.equals(supplierBusinessId) || !isActivelyConnected(buyerId, passport.getId())) {
            // The same answer whether the shop exists or not: a supplier cannot probe for shops.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You are not connected to that shop");
        }
        if (tradeOrderRepository.countBySupplierBusinessIdAndBuyerBusinessIdAndOrderState(
                supplierBusinessId, buyerId, TradeOrder.ORDER_PLACED) >= MAX_WAITING_PER_SHOP) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "That shop already has " + MAX_WAITING_PER_SHOP + " of your orders waiting for its answer");
        }

        List<SalesOrderLine> accepted = acceptedLines(order);
        if (accepted.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Nothing on this order was accepted");
        }
        return toResponse(create(order, passport, buyerId, accepted), true);
    }

    private List<SalesOrderLine> acceptedLines(SalesOrder order) {
        return salesOrderLineRepository
                .findByOrderIdAndBusinessIdOrderByLineIndexAsc(order.getId(), order.getBusinessId())
                .stream()
                .filter(l -> SalesOrderStatuses.LINE_ACCEPTED.equals(l.getStatus())
                        && l.getAcceptedQty() != null && l.getAcceptedQty().signum() > 0)
                .toList();
    }

    /** Writes the shared record: only what was accepted, at the prices the order was placed at. */
    private TradeOrder create(
            SalesOrder order, MarketplaceSupplier passport, String buyerId, List<SalesOrderLine> accepted) {
        String supplierBusinessId = order.getBusinessId();
        Map<String, Item> items = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(
                        accepted.stream().map(SalesOrderLine::getItemId).distinct().toList(), supplierBusinessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));

        TradeOrder trade = new TradeOrder();
        trade.setSupplierBusinessId(supplierBusinessId);
        trade.setSupplierMarketplaceId(passport.getId());
        trade.setBuyerBusinessId(buyerId);
        trade.setSalesOrderId(order.getId());
        trade.setOrderCode(WebOrderCodes.code(order.getId()));
        trade.setSupplierName(passport.getName());
        trade.setCurrency(order.getCurrency());
        trade.setTotal(order.getTotal());
        tradeOrderRepository.save(trade);

        int lineNo = 1;
        for (SalesOrderLine source : accepted) {
            Item item = items.get(source.getItemId());
            TradeOrderLine line = new TradeOrderLine();
            line.setTradeOrderId(trade.getId());
            line.setLineNo(lineNo++);
            line.setProductName(source.getItemName());
            line.setQuantity(source.getAcceptedQty());
            line.setUnitPrice(source.getUnitPrice());
            line.setLineTotal(source.getLineTotal());
            if (item != null) {
                line.setBarcode(blankToNull(item.getBarcode()));
                if (item.isPackageVariant() && item.getPackagingUnitQty() != null) {
                    line.setPackUnit(blankToNull(item.getPackagingUnitName()));
                    line.setPackSize(item.getPackagingUnitQty());
                }
            }
            tradeOrderLineRepository.save(line);
        }
        record(trade, TradeOrderEvent.SENT, TradeOrderEvent.SIDE_SUPPLIER);
        return trade;
    }

    /**
     * An order a signed-in marketplace buyer placed for their own shop crosses as soon as the
     * supplier accepts it, already confirmed: the shop itself asked for it. It only crosses when
     * that shop has an active connection to the supplier; otherwise nothing happens here and the
     * order stays the supplier's. This method never refuses: it does the work or leaves it.
     */
    @Transactional
    public void openForVerifiedBuyer(String supplierBusinessId, String salesOrderId, String buyerBusinessId) {
        if (buyerBusinessId == null || buyerBusinessId.equals(supplierBusinessId)
                || tradeOrderRepository.findBySalesOrderId(salesOrderId).isPresent()) {
            return;
        }
        Optional<SalesOrder> order = salesOrderRepository.findByIdAndBusinessId(salesOrderId, supplierBusinessId);
        Optional<MarketplaceSupplier> passport = marketplaceSupplierRepository.findFirstByBusinessId(supplierBusinessId);
        if (order.isEmpty() || passport.isEmpty() || !isActivelyConnected(buyerBusinessId, passport.get().getId())) {
            return;
        }
        List<SalesOrderLine> accepted = acceptedLines(order.get());
        if (accepted.isEmpty()) {
            return;
        }
        TradeOrder trade = create(order.get(), passport.get(), buyerBusinessId, accepted);
        trade.setOrderState(TradeOrder.ORDER_CONFIRMED);
        record(trade, TradeOrderEvent.CONFIRMED, TradeOrderEvent.SIDE_BUYER);
    }

    /** What the supplier sent for one of its own orders, if it sent anything. */
    @Transactional(readOnly = true)
    public Optional<TradeOrderResponse> forSalesOrder(String supplierBusinessId, String salesOrderId) {
        return tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .map(t -> toResponse(t, true));
    }

    /** The supplier dispatched: the shared record says the goods are on their way. */
    @Transactional
    public void onSalesOrderDispatched(String supplierBusinessId, String salesOrderId) {
        tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .filter(t -> !TradeOrder.ORDER_CANCELLED.equals(t.getOrderState()))
                .ifPresent(t -> {
                    t.setDeliveryState(TradeOrder.DELIVERY_ALL);
                    record(t, TradeOrderEvent.DISPATCHED, TradeOrderEvent.SIDE_SUPPLIER);
                    measure(t, "trade_order_dispatched");
                });
    }

    /**
     * The supplier's money facts for this order: invoiced, part paid, settled. Only the state
     * crosses; the invoice and the amounts paid stay in the supplier's books.
     */
    @Transactional
    public void onMoneyState(String supplierBusinessId, String salesOrderId, String moneyState, String eventKind) {
        tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .ifPresent(t -> {
                    t.setMoneyState(moneyState);
                    record(t, eventKind, TradeOrderEvent.SIDE_SUPPLIER);
                });
    }

    /** The supplier called the order off: the shared record is cancelled with it. */
    @Transactional
    public void onSalesOrderCancelled(String supplierBusinessId, String salesOrderId) {
        tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .filter(t -> !TradeOrder.ORDER_CANCELLED.equals(t.getOrderState()))
                .ifPresent(t -> {
                    t.setOrderState(TradeOrder.ORDER_CANCELLED);
                    record(t, TradeOrderEvent.CANCELLED, TradeOrderEvent.SIDE_SUPPLIER);
                });
    }

    // ── The buyer's side ───────────────────────────────────────────────────────

    /** Orders suppliers have sent this shop, newest first; all, or those in one state. */
    @Transactional(readOnly = true)
    public List<TradeOrderResponse> incoming(String buyerBusinessId, String orderState) {
        List<TradeOrder> rows = orderState == null || orderState.isBlank()
                ? tradeOrderRepository.findTop200ByBuyerBusinessIdOrderByCreatedAtDesc(buyerBusinessId)
                : tradeOrderRepository.findTop200ByBuyerBusinessIdAndOrderStateOrderByCreatedAtDesc(
                        buyerBusinessId, orderState.trim().toLowerCase(Locale.ROOT));
        return rows.stream().map(t -> toResponse(t, false)).toList();
    }

    @Transactional(readOnly = true)
    public TradeOrderResponse incomingOne(String buyerBusinessId, String tradeOrderId) {
        return toResponse(requireForBuyer(buyerBusinessId, tradeOrderId), true);
    }

    /**
     * The shop's answer to an order a supplier sent it. Confirming says "yes, this is ours"; it
     * does not write a purchase order. Declining cancels the shared record only: the supplier's
     * own order, and the stock it took, are the supplier's to cancel.
     */
    @Transactional
    public TradeOrderResponse decide(String buyerBusinessId, String tradeOrderId, boolean confirm) {
        TradeOrder trade = requireForBuyer(buyerBusinessId, tradeOrderId);
        if (!TradeOrder.ORDER_PLACED.equals(trade.getOrderState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has already been answered");
        }
        // A supplier the shop has since suspended gets no confirmation from it.
        if (confirm && !isActivelyConnected(buyerBusinessId, trade.getSupplierMarketplaceId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You are no longer connected to this supplier");
        }
        trade.setOrderState(confirm ? TradeOrder.ORDER_CONFIRMED : TradeOrder.ORDER_CANCELLED);
        record(trade, confirm ? TradeOrderEvent.CONFIRMED : TradeOrderEvent.DECLINED, TradeOrderEvent.SIDE_BUYER);
        return toResponse(trade, true);
    }

    /**
     * The buying shop says what arrived (P2-5). Every line gets a quantity, once. If anything is
     * short the order is marked disputed, so the supplier sees it and can put it right with a
     * credit note. This is the shop's statement on the shared record; it does not receive stock
     * into the shop's books, which the shop does the way it receives any delivery.
     */
    @Transactional
    public TradeOrderResponse recordReceived(String buyerBusinessId, String tradeOrderId, RecordReceivedRequest req) {
        TradeOrder trade = requireForBuyer(buyerBusinessId, tradeOrderId);
        if (TradeOrder.ORDER_CANCELLED.equals(trade.getOrderState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order was cancelled");
        }
        if (TradeOrder.DELIVERY_NONE.equals(trade.getDeliveryState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "The supplier has not dispatched this order yet");
        }
        List<TradeOrderLine> lines = tradeOrderLineRepository.findByTradeOrderIdOrderByLineNoAsc(trade.getId());
        if (lines.stream().anyMatch(l -> l.getReceivedQty() != null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You have already said what arrived");
        }
        Map<Integer, BigDecimal> said = new HashMap<>();
        for (RecordReceivedRequest.Line line : req.lines()) {
            said.put(line.lineNo(), line.receivedQty());
        }
        if (said.size() != lines.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what arrived for every line");
        }
        boolean anyShort = false;
        for (TradeOrderLine line : lines) {
            BigDecimal got = said.get(line.getLineNo());
            if (got == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say what arrived for every line");
            }
            got = got.setScale(4, RoundingMode.HALF_UP);
            if (got.compareTo(line.getQuantity()) > 0) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "More " + line.getProductName() + " than was sent cannot have arrived");
            }
            anyShort = anyShort || got.compareTo(line.getQuantity()) < 0;
            line.setReceivedQty(got);
            tradeOrderLineRepository.save(line);
        }
        record(trade, TradeOrderEvent.RECEIVED, TradeOrderEvent.SIDE_BUYER);
        measure(trade, anyShort ? "trade_order_received_short" : "trade_order_received_in_full");
        if (anyShort) {
            trade.setOrderState(TradeOrder.ORDER_DISPUTED);
            record(trade, TradeOrderEvent.SHORTAGE, TradeOrderEvent.SIDE_BUYER);
        }
        return toResponse(trade, true);
    }

    /** The supplier has dealt with what the shop disputed: the order goes back to confirmed. */
    @Transactional
    public TradeOrderResponse resolveDispute(String supplierBusinessId, String salesOrderId) {
        TradeOrder trade = tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "This order has not been sent to a shop"));
        if (!TradeOrder.ORDER_DISPUTED.equals(trade.getOrderState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order is not disputed");
        }
        trade.setOrderState(TradeOrder.ORDER_CONFIRMED);
        record(trade, TradeOrderEvent.RESOLVED, TradeOrderEvent.SIDE_SUPPLIER);
        return toResponse(trade, true);
    }

    /** A credit note was issued on the supplier's invoice for this order. Only the fact crosses. */
    @Transactional
    public void onCreditNote(String supplierBusinessId, String salesOrderId, boolean nowSettled) {
        tradeOrderRepository.findBySalesOrderId(salesOrderId)
                .filter(t -> t.getSupplierBusinessId().equals(supplierBusinessId))
                .ifPresent(t -> {
                    if (nowSettled) {
                        t.setMoneyState(TradeOrder.MONEY_SETTLED);
                    }
                    record(t, TradeOrderEvent.CREDIT_NOTE, TradeOrderEvent.SIDE_SUPPLIER);
                });
    }

    /**
     * Escrow the buying shop placed against its own purchase order for this trade order (P2-7).
     * Both shops see the state and the amount, which is about this order; nothing else of the hold.
     */
    private TradeOrderResponse.Escrow escrowFor(TradeOrder trade) {
        if (trade.getPurchaseOrderId() == null) {
            return null;
        }
        return escrowHoldRepository
                .findFirstByBusinessIdAndPurchaseOrderIdOrderByCreatedAtDesc(
                        trade.getBuyerBusinessId(), trade.getPurchaseOrderId())
                .map(hold -> new TradeOrderResponse.Escrow(hold.getStatus(), hold.getAmount()))
                .orElse(null);
    }

    /** Each line with this shop's product of the same barcode, so the shop sees what is left to pick. */
    @Transactional(readOnly = true)
    public List<TradeOrderMatchResponse> matches(String buyerBusinessId, String tradeOrderId) {
        TradeOrder trade = requireForBuyer(buyerBusinessId, tradeOrderId);
        List<TradeOrderMatchResponse> out = new ArrayList<>();
        for (TradeOrderLine line : tradeOrderLineRepository.findByTradeOrderIdOrderByLineNoAsc(trade.getId())) {
            Item match = byBarcode(buyerBusinessId, line.getBarcode());
            out.add(new TradeOrderMatchResponse(
                    line.getLineNo(),
                    line.getProductName(),
                    line.getBarcode(),
                    match == null ? null : match.getId(),
                    match == null ? null : match.getName()));
        }
        return out;
    }

    /**
     * The shop makes its own purchase order from an order it confirmed (P2-2). It is written by the
     * shop, in the shop's books, as a draft: the same order the shop could have typed in. A line
     * is matched to the shop's product by barcode, or by the product the shop names for it; an
     * unmatched line stops the whole thing, nothing is created for a product the shop does not sell.
     */
    @Transactional
    public TradeOrderPurchaseOrderResponse makePurchaseOrder(
            String buyerBusinessId, String tradeOrderId, MakePurchaseOrderRequest req) {
        TradeOrder trade = requireForBuyer(buyerBusinessId, tradeOrderId);
        if (!TradeOrder.ORDER_CONFIRMED.equals(trade.getOrderState())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirm the order before making a purchase order");
        }
        if (trade.getPurchaseOrderId() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order already has a purchase order");
        }
        BusinessSupplierConnection connection = connectionRepository
                .findByBusinessIdAndMarketplaceSupplierId(buyerBusinessId, trade.getSupplierMarketplaceId())
                .filter(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.CONFLICT, "You are no longer connected to this supplier"));
        String branchId = req == null || req.branchId() == null || req.branchId().isBlank()
                ? branchResolutionService.resolveDefaultBranch(buyerBusinessId)
                : req.branchId().trim();
        if (branchId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the branch this order is for");
        }

        Map<Integer, String> chosen = new HashMap<>();
        if (req != null && req.matches() != null) {
            for (MakePurchaseOrderRequest.Match m : req.matches()) {
                chosen.put(m.lineNo(), m.itemId().trim());
            }
        }
        List<TradeOrderLine> lines = tradeOrderLineRepository.findByTradeOrderIdOrderByLineNoAsc(trade.getId());
        Map<Integer, Item> itemByLine = new HashMap<>();
        List<String> unmatched = new ArrayList<>();
        for (TradeOrderLine line : lines) {
            String pick = chosen.get(line.getLineNo());
            Item item = pick != null
                    // The shop's own product only: an id from another shop is simply not found.
                    ? itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(pick, buyerBusinessId)
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.BAD_REQUEST, "The product picked for " + line.getProductName() + " was not found"))
                    : byBarcode(buyerBusinessId, line.getBarcode());
            if (item == null) {
                unmatched.add(line.getProductName());
            } else {
                itemByLine.put(line.getLineNo(), item);
            }
        }
        if (!unmatched.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Pick one of your products for: " + String.join(", ", unmatched));
        }

        var created = pathAPurchaseService.createPurchaseOrder(
                buyerBusinessId,
                new CreatePathAPurchaseOrderRequest(
                        connection.getLocalSupplierId(),
                        branchId,
                        null,
                        null,
                        "From " + trade.getSupplierName() + ", order " + trade.getOrderCode()));
        for (TradeOrderLine line : lines) {
            pathAPurchaseService.addPurchaseOrderLine(
                    buyerBusinessId, created.id(), toPurchaseLine(line, itemByLine.get(line.getLineNo())));
        }
        PurchaseOrder po = purchaseOrderRepository.findByIdAndBusinessId(created.id(), buyerBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Order missing"));
        po.setSource(PurchasingConstants.PO_SOURCE_TRADE_ORDER);
        purchaseOrderRepository.save(po);

        trade.setPurchaseOrderId(po.getId());
        record(trade, TradeOrderEvent.PURCHASE_ORDER, TradeOrderEvent.SIDE_BUYER);
        return new TradeOrderPurchaseOrderResponse(po.getId(), po.getPoNumber());
    }

    /**
     * A carton ordered against the shop's single-unit product is expanded into single units, with
     * the carton kept as the purchase unit. A line matched to a product that is itself the pack
     * (or a line with no pack) goes across as it is.
     */
    private static AddPathAPurchaseOrderLineRequest toPurchaseLine(TradeOrderLine line, Item item) {
        BigDecimal packSize = line.getPackSize();
        boolean expand = packSize != null && packSize.compareTo(BigDecimal.ONE) > 0 && !item.isPackageVariant();
        if (!expand) {
            return new AddPathAPurchaseOrderLineRequest(item.getId(), line.getQuantity(), line.getUnitPrice());
        }
        String unit = line.getPackUnit() == null || line.getPackUnit().isBlank() ? "pack" : line.getPackUnit().trim();
        if (unit.length() > 32) {
            unit = unit.substring(0, 32);
        }
        return new AddPathAPurchaseOrderLineRequest(
                item.getId(),
                line.getQuantity().multiply(packSize).setScale(4, RoundingMode.HALF_UP),
                line.getUnitPrice().divide(packSize, 4, RoundingMode.HALF_UP),
                unit,
                line.getQuantity(),
                packSize,
                line.getUnitPrice(),
                null);
    }

    private Item byBarcode(String businessId, String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        return itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode.trim()).orElse(null);
    }

    // ── Shared ─────────────────────────────────────────────────────────────────

    private TradeOrder requireForBuyer(String buyerBusinessId, String tradeOrderId) {
        return tradeOrderRepository.findByIdAndBuyerBusinessId(tradeOrderId, buyerBusinessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    private boolean isActivelyConnected(String shopBusinessId, String marketplaceSupplierId) {
        return connectionRepository.findByBusinessIdAndMarketplaceSupplierId(shopBusinessId, marketplaceSupplierId)
                .filter(c -> BusinessSupplierConnectionStatuses.ACTIVE.equals(c.getStatus()))
                .isPresent();
    }

    /**
     * A supplier's fulfilment record is what happened on its orders: dispatched, received in full,
     * received short (P3-5). Nobody types a rating.
     */
    private void measure(TradeOrder trade, String eventType) {
        zelisline.ub.marketplace.domain.SupplierPerformanceEvent event =
                new zelisline.ub.marketplace.domain.SupplierPerformanceEvent();
        event.setMarketplaceSupplierId(trade.getSupplierMarketplaceId());
        event.setBusinessId(trade.getBuyerBusinessId());
        event.setEventType(eventType);
        event.setPayloadJson("{\"tradeOrderId\":\"" + trade.getId() + "\"}");
        performanceEventRepository.save(event);
    }

    /** Events are numbered per order, in the order they happened. */
    private void record(TradeOrder trade, String kind, String side) {
        TradeOrderEvent event = new TradeOrderEvent();
        event.setTradeOrderId(trade.getId());
        event.setEventNo(trade.getNextEventNo());
        event.setKind(kind);
        event.setActorSide(side);
        tradeOrderEventRepository.save(event);
        trade.setNextEventNo(trade.getNextEventNo() + 1);
        tradeOrderRepository.save(trade);
    }

    private TradeOrderResponse toResponse(TradeOrder trade, boolean withDetail) {
        List<TradeOrderResponse.Line> lines = null;
        List<TradeOrderResponse.Event> events = null;
        if (withDetail) {
            lines = tradeOrderLineRepository.findByTradeOrderIdOrderByLineNoAsc(trade.getId()).stream()
                    .map(l -> new TradeOrderResponse.Line(
                            l.getProductName(), l.getBarcode(), l.getPackUnit(), l.getPackSize(),
                            l.getQuantity(), l.getUnitPrice(), l.getLineTotal(), l.getLineNo(), l.getReceivedQty()))
                    .toList();
            events = tradeOrderEventRepository.findByTradeOrderIdOrderByEventNoAsc(trade.getId()).stream()
                    .map(e -> new TradeOrderResponse.Event(e.getEventNo(), e.getKind(), e.getActorSide(), e.getCreatedAt()))
                    .toList();
        }
        BigDecimal total = trade.getTotal();
        return new TradeOrderResponse(
                trade.getId(),
                trade.getOrderCode(),
                trade.getSupplierName(),
                trade.getOrderState(),
                trade.getDeliveryState(),
                trade.getMoneyState(),
                trade.getCurrency(),
                total,
                trade.getCreatedAt(),
                lines,
                events,
                trade.getPurchaseOrderId() != null,
                escrowFor(trade));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
