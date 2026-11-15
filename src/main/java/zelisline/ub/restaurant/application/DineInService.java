package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.application.ItemSellability;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.discounts.api.dto.ResolvedPriceResponse;
import zelisline.ub.discounts.application.DiscountResolutionService;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.OrderDtos.BillLineResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.BillResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.FloorResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.FloorTable;
import zelisline.ub.restaurant.api.dto.OrderDtos.NewOrderLine;
import zelisline.ub.restaurant.api.dto.OrderDtos.OpenOrderRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderCard;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderDetailsRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderLineResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.OrderResponse;
import zelisline.ub.restaurant.api.dto.OrderDtos.TableRequest;
import zelisline.ub.restaurant.api.dto.OrderDtos.TableResponse;
import zelisline.ub.restaurant.domain.MenuCategory;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.domain.OrderBill;
import zelisline.ub.restaurant.domain.OrderBillLine;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.OrderBillLineRepository;
import zelisline.ub.restaurant.repository.OrderBillRepository;
import zelisline.ub.sales.repository.SaleRepository;
import zelisline.ub.serviceops.application.ServiceResourceService;

/**
 * Orders that are paid after they are eaten: a table, a takeaway, a delivery (ADR 0020, phase 1b).
 * An order is opened, added to, sent to the kitchen in rounds and settled by one or more bills.
 * What has been sent is never edited; it is cancelled with a reason, and the kitchen is told.
 */
@Service
@RequiredArgsConstructor
public class DineInService {

    private final OrderTicketService orderTicketService;
    private final RestaurantSaleSupport saleSupport;
    private final ServiceResourceService resourceService;
    private final StaffProfileService staffProfileService;
    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository categoryRepository;
    private final ItemRepository itemRepository;
    private final DiscountResolutionService discountResolutionService;
    private final OrderBillRepository billRepository;
    private final OrderBillLineRepository billLineRepository;
    private final SaleRepository saleRepository;
    private final RestaurantSettingsService settingsService;
    private final RecipeService recipeService;
    private final RestaurantGate gate;

    // ------------------------------------------------------------------ tables

    @Transactional(readOnly = true)
    public List<TableResponse> tables(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        return resourceService.tables(businessId, branchId).stream().map(DineInService::toResponse).toList();
    }

    @Transactional
    public TableResponse createTable(String businessId, String branchId, TableRequest request) {
        gate.requireEnabled(businessId);
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the table a name or number");
        }
        Integer capacity = request.capacity() == null || request.capacity() <= 0 ? null : request.capacity();
        return toResponse(resourceService.createTable(businessId, branchId, request.name(), capacity, request.area()));
    }

    @Transactional
    public TableResponse updateTable(String businessId, String tableId, TableRequest request) {
        gate.requireEnabled(businessId);
        return toResponse(resourceService.updateTable(
                businessId, tableId, request.name(), request.capacity(), request.area(), request.active()));
    }

    /** Gives a table a new QR code (the old one stops working), or takes its code away. */
    @Transactional
    public TableResponse setTableQr(String businessId, String tableId, boolean enabled) {
        gate.requireEnabled(businessId);
        return toResponse(resourceService.setTableQr(businessId, tableId, enabled));
    }

    /** Which branch a table is at, so the caller's branch can be checked before it is changed. */
    @Transactional(readOnly = true)
    public String branchOfTable(String businessId, String tableId) {
        return resourceService.table(businessId, tableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Table not found"))
                .branchId();
    }

    // ------------------------------------------------------------------ the floor

    /** Every table of a branch with its open orders, and the open orders that have no table. */
    @Transactional(readOnly = true)
    public FloorResponse floor(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        List<OrderTicketService.Order> open = new ArrayList<>(orderTicketService.openOrders(businessId, branchId));
        // A delivery that was paid up front is closed, but it is not done until it is delivered.
        open.addAll(orderTicketService.settledDeliveriesNotDelivered(businessId, branchId));
        Map<String, String> names = staffNames(businessId, open);
        Map<String, List<OrderCard>> byTable = new HashMap<>();
        List<OrderCard> others = new ArrayList<>();
        for (OrderTicketService.Order order : open) {
            OrderCard card = toCard(businessId, order, names);
            if (order.tableResourceId() == null) {
                others.add(card);
            } else {
                byTable.computeIfAbsent(order.tableResourceId(), k -> new ArrayList<>()).add(card);
            }
        }
        List<FloorTable> tables = new ArrayList<>();
        for (ServiceResourceService.TableRecord table : resourceService.tables(businessId, branchId)) {
            List<OrderCard> cards = byTable.remove(table.id());
            if (table.active() || cards != null) {
                tables.add(new FloorTable(toResponse(table), cards == null ? List.of() : cards));
            }
        }
        // An order on a table that no longer exists must not vanish from the floor.
        for (List<OrderCard> orphaned : byTable.values()) {
            others.addAll(orphaned);
        }
        return new FloorResponse(branchId, Instant.now(), tables, others);
    }

    // ------------------------------------------------------------------ an order

    @Transactional
    public OrderResponse open(String businessId, String branchId, String userId, OpenOrderRequest request) {
        gate.requireEnabled(businessId);
        String channel = request.channel().trim().toUpperCase(java.util.Locale.ROOT);
        String tableId = blankToNull(request.tableId());
        if (OrderTicketService.CHANNEL_DINE_IN.equals(channel) && tableId == null && request.scheduledStart() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose the table");
        }
        if (tableId != null) {
            requireTable(businessId, branchId, tableId);
            if (request.scheduledStart() != null && OrderTicketService.CHANNEL_DINE_IN.equals(channel)) {
                requireTableFreeForBooking(businessId, branchId, tableId, request.scheduledStart(),
                        request.scheduledEnd(), null);
            }
        }
        if (OrderTicketService.CHANNEL_DELIVERY.equals(channel)
                && (request.deliveryAddress() == null || request.deliveryAddress().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Where is it being delivered?");
        }
        String servedBy = blankToNull(request.servedByStaffProfileId());
        if (servedBy != null) {
            requireStaff(businessId, servedBy);
        } else if (request.scheduledStart() == null) {
            // Whoever seats the table serves it unless someone else is named.
            servedBy = staffProfileService.ensureProfile(businessId, userId).getId();
        }
        OrderTicketService.Order order = orderTicketService.open(businessId, userId, new OrderTicketService.OpenOrder(
                branchId, channel, tableId, request.guestCount(), servedBy, blankToNull(request.customerId()),
                request.customerName(), request.customerPhone(), request.deliveryAddress(),
                request.clientOrderId(), request.scheduledStart(), request.scheduledEnd(),
                OrderTicketService.CHANNEL_DELIVERY.equals(channel) ? request.deliveryFee() : null, "STAFF"));
        return toResponse(businessId, order);
    }

    @Transactional(readOnly = true)
    public OrderResponse get(String businessId, String orderId) {
        gate.requireEnabled(businessId);
        return toResponse(businessId, require(businessId, orderId));
    }

    @Transactional(readOnly = true)
    public String branchOf(String businessId, String orderId) {
        return require(businessId, orderId).branchId();
    }

    /** Adds lines from the menu, priced now. They are not sent until {@link #send}. */
    @Transactional
    public OrderResponse addLines(String businessId, String orderId, List<NewOrderLine> lines) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        List<OrderTicketService.NewLine> out = new ArrayList<>();
        Map<String, MenuCategory> categories = new HashMap<>();
        for (NewOrderLine line : lines) {
            MenuItem item = menuItemRepository.findByIdAndBusinessId(line.menuItemId().trim(), businessId)
                    .filter(i -> i.getDeletedAt() == null)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "That is no longer on the menu"));
            String stationId = item.getStationId();
            if (stationId == null && item.getCategoryId() != null) {
                MenuCategory category = categories.computeIfAbsent(item.getCategoryId(),
                        id -> categoryRepository.findByIdAndBusinessId(id, businessId).orElse(null));
                stationId = category == null ? null : category.getStationId();
            }
            if (MenuService.KIND_DISH.equals(item.getKind())) {
                if (line.quantity().stripTrailingZeros().scale() > 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A dish is ordered in whole numbers");
                }
                RestaurantSaleSupport.DishLine dish =
                        saleSupport.priceDish(businessId, item.getId(), line.modifierOptionIds());
                out.add(new OrderTicketService.NewLine("DISH", null, item.getId(), dish.name(), line.quantity(),
                        dish.unitPrice(), dish.modifiersJson(), blankToNull(line.note()), stationId, true,
                        line.seatNo(), line.clientLineId()));
            } else {
                if (line.modifierOptionIds() != null && !line.modifierOptionIds().isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only a dish has choices");
                }
                if (!item.isActive() || !item.isAvailable()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, item.getName() + " is not available");
                }
                Item product = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(item.getItemId(), businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                item.getName() + " is no longer for sale"));
                String violation = ItemSellability.sellabilityViolation(product);
                if (violation != null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, violation);
                }
                out.add(new OrderTicketService.NewLine("ITEM", product.getId(), item.getId(), item.getName(),
                        line.quantity(), shelfPrice(businessId, order.branchId(), product.getId(), item.getName()),
                        null, blankToNull(line.note()), stationId, stationId != null, line.seatNo(),
                        line.clientLineId()));
            }
        }
        return toResponse(businessId, orderTicketService.addLines(businessId, orderId, out));
    }

    @Transactional
    public OrderResponse changeLine(
            String businessId, String orderId, String lineId, BigDecimal quantity, String note, Integer seatNo) {
        gate.requireEnabled(businessId);
        return toResponse(businessId,
                orderTicketService.changeDraftLine(businessId, orderId, lineId, quantity, note, seatNo));
    }

    /** Sends everything not yet sent; the kitchen gets one ticket per station. Sending nothing is not an error. */
    @Transactional
    public OrderResponse send(String businessId, String orderId, String userId) {
        gate.requireEnabled(businessId);
        Instant now = Instant.now();
        List<OrderTicketService.OrderLine> sent = orderTicketService.send(businessId, orderId, now);
        OrderTicketService.Order order = require(businessId, orderId);
        if (!sent.isEmpty()) {
            saleSupport.sendRound(businessId, order, sent, userId, now);
        }
        return toResponse(businessId, order);
    }

    /**
     * Cancels a line. One that was never sent just goes. One that was sent needs a reason, is kept
     * on the order as cancelled, and the kitchen is told if it was still making it.
     *
     * @param maySendVoid whether the caller may cancel a line the kitchen already has
     */
    @Transactional
    public OrderResponse voidLine(
            String businessId, String orderId, String lineId, String reason, String userId, boolean maySendVoid) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order before = orderTicketService.lock(businessId, orderId);
        OrderTicketService.OrderLine target = before.lines().stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order line not found"));
        if (!target.isDraft() && !target.isVoided()) {
            if (!maySendVoid) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "That has gone to the kitchen; ask a manager to cancel it");
            }
            if (billedQuantity(businessId, orderId).getOrDefault(lineId, BigDecimal.ZERO).signum() > 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "That is on a bill; take it off the bill before cancelling it");
            }
        }
        Instant now = Instant.now();
        OrderTicketService.VoidedLine voided = orderTicketService.voidLine(businessId, orderId, lineId, reason, userId);
        OrderTicketService.Order order = require(businessId, orderId);
        if (voided.wasSent()) {
            saleSupport.lineCancelled(businessId, order, voided.line(), voided.kitchenWasMakingIt(), userId, now);
            // Food that was already being cooked, or was cooked, is gone whether or not anyone pays:
            // its ingredients come off the count as wastage. A line the kitchen had not started
            // (still NEW) used nothing.
            boolean cooked = OrderTicketService.STAGE_PREPARING.equals(target.stage())
                    || OrderTicketService.STAGE_READY.equals(target.stage())
                    || OrderTicketService.STAGE_SERVED.equals(target.stage());
            if (cooked && !target.isVoided() && "DISH".equals(target.lineKind()) && target.menuItemId() != null) {
                recipeService.wasteCookedLine(businessId, order.branchId(), target.id(), userId, target.menuItemId(),
                        ModifierSnapshots.decode(target.modifiers()), target.quantity());
            }
        }
        return toResponse(businessId, order);
    }

    @Transactional
    public OrderResponse move(String businessId, String orderId, String tableId) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        String target = blankToNull(tableId);
        if (target != null) {
            requireTable(businessId, order.branchId(), target);
            if (order.isBooked()) {
                requireTableFreeForBooking(businessId, order.branchId(), target, order.scheduledStart(),
                        order.scheduledEnd(), order.id());
            }
        }
        return toResponse(businessId, orderTicketService.moveToTable(businessId, orderId, target));
    }

    /** Joins this order into another open order at the same branch; this one is closed. */
    @Transactional
    public OrderResponse merge(String businessId, String orderId, String intoOrderId, String userId) {
        gate.requireEnabled(businessId);
        if (billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId).stream()
                .anyMatch(OrderBill::holdsItsLines)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This order has a bill; settle or tear it up before joining it with another");
        }
        OrderTicketService.Order into = orderTicketService.merge(businessId, orderId, intoOrderId, userId, Instant.now());
        saleSupport.ordersJoined(businessId, orderId, into);
        return toResponse(businessId, into);
    }

    /** A delivery order's fee, rider and progress. */
    @Transactional
    public OrderResponse setDelivery(
            String businessId, String orderId, BigDecimal deliveryFee, String riderName, Boolean dispatched,
            Boolean delivered) {
        gate.requireEnabled(businessId);
        return toResponse(businessId, orderTicketService.setDelivery(businessId, orderId, deliveryFee, riderName,
                dispatched, delivered, Instant.now()));
    }

    /** Who can be named as serving an order: everyone marked as serving customers. */
    @Transactional(readOnly = true)
    public List<zelisline.ub.restaurant.api.dto.OrderDtos.ServerResponse> servers(String businessId) {
        gate.requireEnabled(businessId);
        List<zelisline.ub.restaurant.api.dto.OrderDtos.ServerResponse> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : staffProfileService
                .displayNamesByIds(businessId, staffProfileService.findBookableProfileIds(businessId)).entrySet()) {
            out.add(new zelisline.ub.restaurant.api.dto.OrderDtos.ServerResponse(entry.getKey(), entry.getValue()));
        }
        out.sort(java.util.Comparator.comparing(zelisline.ub.restaurant.api.dto.OrderDtos.ServerResponse::name));
        return out;
    }

    @Transactional
    public OrderResponse setDetails(String businessId, String orderId, OrderDetailsRequest request) {
        gate.requireEnabled(businessId);
        if (request.servedByStaffProfileId() != null && !request.servedByStaffProfileId().isBlank()) {
            requireStaff(businessId, request.servedByStaffProfileId().trim());
        }
        return toResponse(businessId, orderTicketService.setDetails(businessId, orderId, request.guestCount(),
                request.servedByStaffProfileId(), request.customerId(), request.customerName(),
                request.customerPhone(), request.deliveryAddress()));
    }

    @Transactional
    public OrderResponse requestBill(String businessId, String orderId, boolean requested) {
        gate.requireEnabled(businessId);
        return toResponse(businessId, orderTicketService.requestBill(businessId, orderId, requested, Instant.now()));
    }

    /** A booking's guests have arrived. They need a table by now. */
    @Transactional
    public OrderResponse seat(String businessId, String orderId, String userId) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        if (OrderTicketService.CHANNEL_DINE_IN.equals(order.channel()) && order.tableResourceId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Choose a table for the booking first");
        }
        if (order.servedByStaffProfileId() == null) {
            orderTicketService.setDetails(businessId, orderId, null,
                    staffProfileService.ensureProfile(businessId, userId).getId(), null, null, null, null);
        }
        return toResponse(businessId, orderTicketService.seatBooking(businessId, orderId));
    }

    /** Everything the kitchen has finished has reached the table (or the customer). */
    @Transactional
    public OrderResponse markServed(String businessId, String orderId) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        Instant now = Instant.now();
        boolean any = false;
        for (OrderTicketService.OrderLine line : order.lines()) {
            if (OrderTicketService.STAGE_READY.equals(line.stage())) {
                orderTicketService.setLineStage(businessId, line.id(), OrderTicketService.STAGE_SERVED, now);
                any = true;
            }
        }
        if (any) {
            saleSupport.refreshTickets(businessId, orderId, now);
        }
        return toResponse(businessId, require(businessId, orderId));
    }

    @Transactional
    public OrderResponse cancel(String businessId, String orderId, String reason, String userId) {
        gate.requireEnabled(businessId);
        orderTicketService.lock(businessId, orderId);
        if (billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId).stream()
                .anyMatch(OrderBill::holdsItsLines)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has a bill; it cannot be cancelled");
        }
        return toResponse(businessId, orderTicketService.cancel(businessId, orderId, reason, userId, Instant.now()));
    }

    // ------------------------------------------------------------------ shared with BillService

    OrderTicketService.Order require(String businessId, String orderId) {
        return orderTicketService.find(businessId, orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
    }

    /** How much of each order line is on a bill that still claims it (open or settled). */
    Map<String, BigDecimal> billedQuantity(String businessId, String orderId) {
        return quantities(businessId, orderId, false);
    }

    /** How much of each order line is on a bill that is paid or otherwise closed. */
    Map<String, BigDecimal> settledQuantity(String businessId, String orderId) {
        return quantities(businessId, orderId, true);
    }

    private Map<String, BigDecimal> quantities(String businessId, String orderId, boolean settledOnly) {
        Map<String, OrderBill> bills = new HashMap<>();
        for (OrderBill bill : billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId)) {
            bills.put(bill.getId(), bill);
        }
        Map<String, BigDecimal> out = new HashMap<>();
        for (OrderBillLine line : billLineRepository.findByBusinessIdAndOrderId(businessId, orderId)) {
            OrderBill bill = bills.get(line.getBillId());
            if (bill == null || !bill.holdsItsLines() || (settledOnly && !bill.isSettled())) {
                continue;
            }
            out.merge(line.getOrderLineId(), line.getQuantity(), BigDecimal::add);
        }
        return out;
    }

    BigDecimal shelfPrice(String businessId, String branchId, String itemId, String name) {
        ResolvedPriceResponse resolved =
                discountResolutionService.resolveForItems(businessId, branchId, List.of(itemId)).get(itemId);
        BigDecimal price = resolved == null ? null
                : resolved.finalPrice() != null ? resolved.finalPrice() : resolved.regularPrice();
        if (price == null || price.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set a selling price for " + name + " first");
        }
        return price.setScale(2, RoundingMode.HALF_UP);
    }

    OrderResponse toResponse(String businessId, OrderTicketService.Order order) {
        Map<String, BigDecimal> billed = billedQuantity(businessId, order.id());
        Map<String, BigDecimal> settled = settledQuantity(businessId, order.id());
        Map<String, OrderTicketService.OrderLine> linesById = new LinkedHashMap<>();
        List<OrderLineResponse> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal settledTotal = BigDecimal.ZERO;
        BigDecimal unbilled = BigDecimal.ZERO;
        for (OrderTicketService.OrderLine line : order.lines()) {
            linesById.put(line.id(), line);
            BigDecimal onBill = billed.getOrDefault(line.id(), BigDecimal.ZERO);
            BigDecimal closed = settled.getOrDefault(line.id(), BigDecimal.ZERO);
            lines.add(new OrderLineResponse(line.id(), line.lineKind(), line.menuItemId(), line.itemId(), line.name(),
                    line.quantity(), line.unitPrice(), line.lineTotal(), ModifierSnapshots.decode(line.modifiers()),
                    line.note(), line.seatNo(), line.stage(), line.sentAt(), onBill, closed, line.voidReason(),
                    line.fromCustomer()));
            if (!line.isVoided()) {
                total = total.add(line.lineTotal());
                settledTotal = settledTotal.add(amount(line, closed));
                unbilled = unbilled.add(amount(line, line.quantity().subtract(onBill).max(BigDecimal.ZERO)));
            }
        }
        List<OrderBill> bills = billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, order.id());
        Map<String, List<OrderBillLine>> billLines = new HashMap<>();
        for (OrderBillLine line : billLineRepository.findByBusinessIdAndOrderId(businessId, order.id())) {
            billLines.computeIfAbsent(line.getBillId(), k -> new ArrayList<>()).add(line);
        }
        zelisline.ub.restaurant.domain.RestaurantSettings settings = settingsService.get(businessId);
        List<BillResponse> billResponses = new ArrayList<>();
        for (OrderBill bill : bills) {
            List<BillLineResponse> rows = new ArrayList<>();
            BigDecimal billTotal = BigDecimal.ZERO;
            for (OrderBillLine row : billLines.getOrDefault(bill.getId(), List.of())) {
                OrderTicketService.OrderLine line = linesById.get(row.getOrderLineId());
                if (line == null) {
                    continue;
                }
                BigDecimal amount = amount(line, row.getQuantity());
                billTotal = billTotal.add(amount);
                rows.add(new BillLineResponse(line.id(), line.name(), row.getQuantity(), amount));
            }
            Long receiptNo = bill.getSaleId() == null ? null
                    : saleRepository.findByIdAndBusinessId(bill.getSaleId(), businessId)
                            .map(s -> s.getReceiptNo()).orElse(null);
            BillCharges.Charges charges = BillCharges.forBill(order, bill, bills, billTotal, settings);
            billResponses.add(new BillResponse(bill.getId(), bill.getBillNo(), bill.getStatus(), billTotal,
                    bill.getSaleId(), receiptNo, bill.getReason(), rows, charges.serviceCharge(),
                    charges.packagingCharge(), charges.deliveryFee(), billTotal.add(charges.total())));
        }
        String tableName = order.tableResourceId() == null ? null
                : resourceService.table(businessId, order.tableResourceId())
                        .map(ServiceResourceService.TableRecord::name).orElse(null);
        String servedByName = order.servedByStaffProfileId() == null ? null
                : staffProfileService.displayNamesByIds(businessId, List.of(order.servedByStaffProfileId()))
                        .get(order.servedByStaffProfileId());
        return new OrderResponse(order.id(), order.orderNumber(), order.branchId(), order.channel(), order.status(),
                order.isBooked(), order.tableResourceId(), tableName, order.guestCount(),
                order.servedByStaffProfileId(), servedByName, order.customerId(), order.customerName(),
                order.customerPhone(), order.deliveryAddress(), order.createdAt(), order.billRequestedAt(),
                order.scheduledStart(), order.scheduledEnd(), order.version(),
                total.setScale(2, RoundingMode.HALF_UP), settledTotal.setScale(2, RoundingMode.HALF_UP),
                unbilled.setScale(2, RoundingMode.HALF_UP), lines, billResponses,
                OrderTicketService.CHANNEL_DELIVERY.equals(order.channel())
                        ? (order.deliveryFee() != null ? order.deliveryFee() : settings.getDeliveryFee()) : null,
                order.riderName(), order.dispatchedAt(), order.deliveredAt(), order.source());
    }

    /** What a quantity of a line costs. */
    static BigDecimal amount(OrderTicketService.OrderLine line, BigDecimal quantity) {
        return quantity.multiply(line.unitPrice()).setScale(2, RoundingMode.HALF_UP);
    }

    private OrderCard toCard(String businessId, OrderTicketService.Order order, Map<String, String> staffNames) {
        Map<String, BigDecimal> settled = settledQuantity(businessId, order.id());
        int unsent = 0;
        int inKitchen = 0;
        int ready = 0;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal settledValue = BigDecimal.ZERO;
        for (OrderTicketService.OrderLine line : order.lines()) {
            if (line.isVoided()) {
                continue;
            }
            total = total.add(line.lineTotal());
            BigDecimal closed = settled.getOrDefault(line.id(), BigDecimal.ZERO);
            if (closed.signum() > 0) {
                settledValue = settledValue.add(line.unitPrice().multiply(closed));
            }
            if (line.isDraft()) {
                unsent++;
            } else if (OrderTicketService.STAGE_NEW.equals(line.stage())
                    || OrderTicketService.STAGE_PREPARING.equals(line.stage())) {
                inKitchen++;
            } else if (OrderTicketService.STAGE_READY.equals(line.stage())) {
                ready++;
            }
        }
        return new OrderCard(order.id(), order.orderNumber(), order.channel(), order.tableResourceId(),
                order.guestCount(), order.servedByStaffProfileId(),
                order.servedByStaffProfileId() == null ? null : staffNames.get(order.servedByStaffProfileId()),
                order.customerName(), order.createdAt(), total.setScale(2, RoundingMode.HALF_UP), unsent, inKitchen,
                ready, order.billRequestedAt(), order.isBooked(), order.scheduledStart(), order.scheduledEnd(),
                !order.isOpen(), order.dispatchedAt(),
                total.subtract(settledValue).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    private Map<String, String> staffNames(String businessId, List<OrderTicketService.Order> orders) {
        List<String> ids = orders.stream().map(OrderTicketService.Order::servedByStaffProfileId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : staffProfileService.displayNamesByIds(businessId, ids);
    }

    private void requireTable(String businessId, String branchId, String tableId) {
        ServiceResourceService.TableRecord table = resourceService.table(businessId, tableId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Table not found"));
        if (!table.branchId().equals(branchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That table is at another branch");
        }
        if (!table.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, table.name() + " is not in use");
        }
    }

    /** Two bookings cannot hold one table at overlapping times. Walk-ins are not stopped: staff can see the table. */
    private void requireTableFreeForBooking(
            String businessId, String branchId, String tableId, Instant from, Instant to, String exceptOrderId) {
        for (OrderTicketService.Order other : orderTicketService.bookingsOverlapping(businessId, branchId, from, to)) {
            if (tableId.equals(other.tableResourceId()) && !other.id().equals(exceptOrderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That table is already booked for then");
            }
        }
    }

    private void requireStaff(String businessId, String staffProfileId) {
        if (staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId)).get(staffProfileId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member not found");
        }
    }

    private static TableResponse toResponse(ServiceResourceService.TableRecord table) {
        return new TableResponse(table.id(), table.name(), table.capacity(), table.area(), table.active(),
                table.sortOrder(), table.qrToken());
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
