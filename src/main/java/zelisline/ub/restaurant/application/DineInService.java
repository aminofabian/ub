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
    private final zelisline.ub.serviceops.application.RoomMaintenanceService roomMaintenanceService;
    private final StaffProfileService staffProfileService;
    private final zelisline.ub.credits.application.CustomerDirectoryService customerDirectoryService;
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
        List<ServiceResourceService.TableRecord> tables = resourceService.tables(businessId, branchId);
        Map<String, String> waiters = waiterNames(businessId, tables);
        return tables.stream().map(t -> toResponse(t, waiters)).toList();
    }

    @Transactional
    public TableResponse createTable(String businessId, String branchId, TableRequest request) {
        gate.requireEnabled(businessId);
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Give the table a name or number");
        }
        Integer capacity = request.capacity() == null || request.capacity() <= 0 ? null : request.capacity();
        ServiceResourceService.TableRecord table =
                resourceService.createTable(businessId, branchId, request.name(), capacity, request.area());
        if (request.waiterStaffProfileId() != null && !request.waiterStaffProfileId().isBlank()) {
            requireStaff(businessId, request.waiterStaffProfileId().trim());
            table = resourceService.assignTableWaiter(businessId, table.id(), request.waiterStaffProfileId());
        }
        return toResponse(businessId, table);
    }

    @Transactional
    public TableResponse updateTable(String businessId, String tableId, TableRequest request) {
        gate.requireEnabled(businessId);
        ServiceResourceService.TableRecord table = resourceService.updateTable(
                businessId, tableId, request.name(), request.capacity(), request.area(), request.active());
        if (request.waiterStaffProfileId() != null) {
            if (!request.waiterStaffProfileId().isBlank()) {
                requireStaff(businessId, request.waiterStaffProfileId().trim());
            }
            table = resourceService.assignTableWaiter(businessId, tableId, request.waiterStaffProfileId());
        }
        return toResponse(businessId, table);
    }

    /** Gives a table a new QR code (the old one stops working), or takes its code away. */
    @Transactional
    public TableResponse setTableQr(String businessId, String tableId, boolean enabled) {
        gate.requireEnabled(businessId);
        return toResponse(businessId, resourceService.setTableQr(businessId, tableId, enabled));
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
        // A stay lives on the front desk, not the restaurant floor: keep lodging orders out of it.
        List<OrderTicketService.Order> open = orderTicketService.openOrders(businessId, branchId).stream()
                .filter(o -> !OrderTicketService.CHANNEL_LODGING.equals(o.channel()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
        List<ServiceResourceService.TableRecord> all = resourceService.tables(businessId, branchId);
        Map<String, String> waiters = waiterNames(businessId, all);
        for (ServiceResourceService.TableRecord table : all) {
            List<OrderCard> cards = byTable.remove(table.id());
            if (table.active() || cards != null) {
                tables.add(new FloorTable(toResponse(table, waiters), cards == null ? List.of() : cards));
            }
        }
        // An order on a table that no longer exists must not vanish from the floor.
        for (List<OrderCard> orphaned : byTable.values()) {
            others.addAll(orphaned);
        }
        return new FloorResponse(branchId, Instant.now(), tables, others);
    }

    // ------------------------------------------------------------------ the front desk

    /** Every room of a branch with its stays (in-house and booked), and stays whose room is gone. */
    @Transactional(readOnly = true)
    public zelisline.ub.restaurant.api.dto.RoomDtos.FrontDeskResponse frontDesk(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        List<OrderTicketService.Order> stays = orderTicketService.openOrders(businessId, branchId).stream()
                .filter(o -> OrderTicketService.CHANNEL_LODGING.equals(o.channel()))
                .toList();
        Map<String, String> names = staffNames(businessId, stays);
        Map<String, List<OrderCard>> byRoom = new HashMap<>();
        List<OrderCard> others = new ArrayList<>();
        for (OrderTicketService.Order stay : stays) {
            OrderCard card = toCard(businessId, stay, names);
            if (stay.tableResourceId() == null) {
                others.add(card);
            } else {
                byRoom.computeIfAbsent(stay.tableResourceId(), k -> new ArrayList<>()).add(card);
            }
        }
        Map<String, Integer> openIssues = roomMaintenanceService.openCountsByRoom(businessId, branchId);
        List<zelisline.ub.restaurant.api.dto.RoomDtos.RoomWithStays> rooms = new ArrayList<>();
        for (ServiceResourceService.RoomRecord room : resourceService.rooms(businessId, branchId)) {
            List<OrderCard> cards = byRoom.remove(room.id());
            if (room.active() || cards != null) {
                rooms.add(new zelisline.ub.restaurant.api.dto.RoomDtos.RoomWithStays(
                        toRoomResponse(room, openIssues.getOrDefault(room.id(), 0)),
                        cards == null ? List.of() : cards));
            }
        }
        // A stay on a room that no longer exists must not vanish from the desk.
        for (List<OrderCard> orphaned : byRoom.values()) {
            others.addAll(orphaned);
        }
        // The booking default times: the business setting, or 2pm in / 11am out (ADR 0031).
        zelisline.ub.restaurant.domain.RestaurantSettings settings = settingsService.get(businessId);
        String checkIn = fmtTime(settings.getCheckInTime(), java.time.LocalTime.of(14, 0));
        String checkOut = fmtTime(settings.getCheckOutTime(), java.time.LocalTime.of(11, 0));
        return new zelisline.ub.restaurant.api.dto.RoomDtos.FrontDeskResponse(
                branchId, Instant.now(), rooms, others, checkIn, checkOut);
    }

    /** A LocalTime as "HH:mm", falling back to {@code dflt} when unset. */
    private static String fmtTime(java.time.LocalTime time, java.time.LocalTime dflt) {
        java.time.LocalTime t = time != null ? time : dflt;
        return String.format("%02d:%02d", t.getHour(), t.getMinute());
    }

    /**
     * The stays a cashier can charge a drink or a meal to: guests checked in now (ADR 0030). A
     * booking not yet arrived is left out — you cannot charge a guest who is not here — as is a
     * settled stay. The charge rides the stay's folio and is realized once, at checkout.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.restaurant.api.dto.RoomDtos.InHouseStaysResponse inHouseStays(
            String businessId, String branchId) {
        gate.requireEnabled(businessId);
        Map<String, String> roomNames = new HashMap<>();
        for (ServiceResourceService.RoomRecord room : resourceService.rooms(businessId, branchId)) {
            roomNames.put(room.id(), room.name());
        }
        List<zelisline.ub.restaurant.api.dto.RoomDtos.InHouseStay> out = new ArrayList<>();
        for (OrderTicketService.Order stay : orderTicketService.openOrders(businessId, branchId)) {
            if (!OrderTicketService.CHANNEL_LODGING.equals(stay.channel()) || stay.isBooked() || !stay.isOpen()) {
                continue;
            }
            BigDecimal total = BigDecimal.ZERO;
            for (OrderTicketService.OrderLine line : stay.lines()) {
                if (!line.isVoided()) {
                    total = total.add(line.lineTotal());
                }
            }
            String roomId = stay.tableResourceId();
            out.add(new zelisline.ub.restaurant.api.dto.RoomDtos.InHouseStay(
                    stay.id(), stay.orderNumber(), roomId, roomId == null ? null : roomNames.get(roomId),
                    stay.customerName(), total.setScale(2, RoundingMode.HALF_UP)));
        }
        return new zelisline.ub.restaurant.api.dto.RoomDtos.InHouseStaysResponse(branchId, out);
    }

    /**
     * A guest's stays with this business, newest first (ADR 0030) — repeat-guest recognition. Keyed
     * on the customer a stay already links to; no new guest data is stored. Read-only.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.restaurant.api.dto.RoomDtos.GuestStaysResponse guestStays(
            String businessId, String customerId) {
        gate.requireEnabled(businessId);
        List<OrderTicketService.Order> stays = orderTicketService.lodgingStaysForCustomer(businessId, customerId, 50);
        java.util.Map<String, String> roomNames = new HashMap<>();
        List<zelisline.ub.restaurant.api.dto.RoomDtos.GuestStay> out = new ArrayList<>();
        String name = null;
        for (OrderTicketService.Order stay : stays) {
            if (name == null) {
                name = stay.customerName();
            }
            String roomId = stay.tableResourceId();
            String roomName = roomId == null ? null
                    : roomNames.computeIfAbsent(roomId, id -> resourceService.room(businessId, id)
                            .map(ServiceResourceService.RoomRecord::name).orElse(null));
            BigDecimal total = BigDecimal.ZERO;
            for (OrderTicketService.OrderLine line : stay.lines()) {
                if (!line.isVoided()) {
                    total = total.add(line.lineTotal());
                }
            }
            out.add(new zelisline.ub.restaurant.api.dto.RoomDtos.GuestStay(stay.id(), stay.orderNumber(), roomName,
                    stay.scheduledStart(), stay.scheduledEnd(), stayState(stay), total.setScale(2, RoundingMode.HALF_UP)));
        }
        return new zelisline.ub.restaurant.api.dto.RoomDtos.GuestStaysResponse(customerId, name, out.size(), out);
    }

    private static String stayState(OrderTicketService.Order stay) {
        if ("cancelled".equals(stay.status())) {
            return "cancelled";
        }
        if (!stay.isOpen()) {
            return "checked-out";
        }
        return stay.isBooked() ? "booked" : "in-house";
    }

    /**
     * Charges lines (a drink, a meal) to a checked-in stay's folio (ADR 0030). This is the counter
     * "charge to the room" action: the obligation moves to the guest's stay, and no sale is rung now
     * — the folio settles once, at checkout. The stay must be a guest checked in here, not a booking
     * for later and not an order already settled.
     */
    @Transactional
    public OrderResponse chargeToStay(String businessId, String stayOrderId, List<NewOrderLine> lines) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order stay = require(businessId, stayOrderId);
        if (!OrderTicketService.CHANNEL_LODGING.equals(stay.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a room stay");
        }
        if (stay.isBooked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Check the guest in before charging to their room");
        }
        if (!stay.isOpen()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That stay is settled; it can take no more charges");
        }
        return addLines(businessId, stayOrderId, lines);
    }

    /**
     * A stay has checked out (its folio settled): send its room to housekeeping rather than free it
     * (ADR 0030). Called when an order completes; does nothing unless it is a lodging stay with a
     * room. The room becomes DIRTY and is not bookable again until it is turned round.
     */
    public void onStayCompleted(String businessId, OrderTicketService.Order order) {
        if (order != null && OrderTicketService.CHANNEL_LODGING.equals(order.channel())) {
            resourceService.markRoomDirtyOnCheckout(businessId, order.tableResourceId());
        }
    }

    private zelisline.ub.restaurant.api.dto.RoomDtos.RoomResponse toRoomResponse(
            ServiceResourceService.RoomRecord room, int openIssueCount) {
        String cleaner = staffName(room.businessId(), room.cleanerStaffProfileId());
        return new zelisline.ub.restaurant.api.dto.RoomDtos.RoomResponse(
                room.id(), room.branchId(), room.name(), room.nightlyRate(), room.area(), room.active(),
                room.sortOrder(), room.housekeepingStatus(), room.readyToLet(), room.roomTypeId(),
                room.effectiveNightlyRate(), room.effectiveWeekendRate(),
                room.housekeepingNote(), room.cleanerStaffProfileId(), cleaner, openIssueCount);
    }

    // ------------------------------------------------------------------ an order

    @Transactional
    public OrderResponse open(String businessId, String branchId, String userId, OpenOrderRequest request) {
        return open(businessId, branchId, userId, request, "STAFF");
    }

    /** As {@link #open}, naming where the order came from ("STAFF" at the desk, "ONLINE" from the web). */
    @Transactional
    public OrderResponse open(String businessId, String branchId, String userId, OpenOrderRequest request,
            String source) {
        gate.requireEnabled(businessId);
        String channel = request.channel().trim().toUpperCase(java.util.Locale.ROOT);
        String tableId = blankToNull(request.tableId());
        boolean lodging = OrderTicketService.CHANNEL_LODGING.equals(channel);
        // A stay is a booking on a room: the tableId field carries the room, and both dates are required.
        if (lodging) {
            if (tableId == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a room for the stay");
            }
            requireRoom(businessId, branchId, tableId);
            // A replay of the same booking (same client order id) must not collide with the stay it
            // already created: exclude that one from the overlap check so the request stays idempotent.
            String existingId = orderTicketService.findByClientOrderId(businessId, request.clientOrderId())
                    .map(OrderTicketService.Order::id).orElse(null);
            requireRoomFreeForStay(businessId, branchId, tableId, request.scheduledStart(),
                    request.scheduledEnd(), existingId);
        }
        // A dine-in order needs a table, unless it is a booking for later or a named tab (a bar runs
        // tabs under a name with no table; it can be seated at a table later with move).
        if (OrderTicketService.CHANNEL_DINE_IN.equals(channel) && tableId == null
                && request.scheduledStart() == null && blankToNull(request.customerName()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a table, or name the tab");
        }
        if (tableId != null && !lodging) {
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
            // The table's own waiter serves it; with none assigned, whoever seats it does.
            servedBy = tableId == null ? null : resourceService.table(businessId, tableId)
                    .map(ServiceResourceService.TableRecord::assignedStaffProfileId)
                    .filter(id -> staffProfileService.displayNamesByIds(businessId, List.of(id)).get(id) != null)
                    .orElse(null);
            if (servedBy == null) {
                servedBy = staffProfileService.ensureProfile(businessId, userId).getId();
            }
        }
        OrderTicketService.Order order = orderTicketService.open(businessId, userId, new OrderTicketService.OpenOrder(
                branchId, channel, tableId, request.guestCount(), servedBy, blankToNull(request.customerId()),
                request.customerName(), request.customerPhone(), request.deliveryAddress(),
                request.clientOrderId(), request.scheduledStart(), request.scheduledEnd(),
                OrderTicketService.CHANNEL_DELIVERY.equals(channel) ? request.deliveryFee() : null, source));
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

    /**
     * Moves a stay to another room (ADR 0030): the guest changes rooms, their folio travels with
     * them. The target room must be free for the stay's nights and in use. A checked-in stay keeps
     * its frozen room-night line as it is — the rate is not re-figured for a mid-stay move; only
     * which room holds the stay changes.
     */
    @Transactional
    public OrderResponse moveStayToRoom(String businessId, String orderId, String roomId) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order stay = orderTicketService.lock(businessId, orderId);
        if (!OrderTicketService.CHANNEL_LODGING.equals(stay.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a room stay");
        }
        String target = blankToNull(roomId);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a room");
        }
        requireRoom(businessId, stay.branchId(), target);
        requireRoomFreeForStay(businessId, stay.branchId(), target, stay.scheduledStart(), stay.scheduledEnd(),
                stay.id());
        // A checked-in guest is in the room now whatever their scheduled dates say, so an in-house
        // stay on the target blocks the move even when the date windows do not overlap.
        requireRoomNotInHouse(businessId, stay.branchId(), target, stay.id());
        return toResponse(businessId, orderTicketService.moveToTable(businessId, orderId, target));
    }

    /** Refuses a room that a guest is checked into now (a different stay than {@code exceptOrderId}). */
    private void requireRoomNotInHouse(String businessId, String branchId, String roomId, String exceptOrderId) {
        for (OrderTicketService.Order other : orderTicketService.openOrders(businessId, branchId)) {
            if (OrderTicketService.CHANNEL_LODGING.equals(other.channel())
                    && roomId.equals(other.tableResourceId())
                    && !other.isBooked()
                    && !other.id().equals(exceptOrderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "A guest is already in that room");
            }
        }
    }

    /**
     * Changes a booking's check-in and check-out before the guest arrives (ADR 0030): extend,
     * shorten, or shift the stay. The room must still be free for the new nights. Only a booking
     * not yet checked in is changed here; once a guest is in, the folio's nights are frozen.
     */
    @Transactional
    public OrderResponse changeStayDates(String businessId, String orderId, Instant checkIn, Instant checkOut) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order stay = orderTicketService.lock(businessId, orderId);
        if (!OrderTicketService.CHANNEL_LODGING.equals(stay.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a room stay");
        }
        if (!stay.isBooked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This guest is checked in; their dates can no longer be changed here");
        }
        if (checkIn == null || checkOut == null || !checkOut.isAfter(checkIn)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-out must be after check-in");
        }
        if (stay.tableResourceId() != null) {
            requireRoomFreeForStay(businessId, stay.branchId(), stay.tableResourceId(), checkIn, checkOut, stay.id());
        }
        return toResponse(businessId, orderTicketService.rescheduleBooking(businessId, orderId, checkIn, checkOut));
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

    /** Records (or clears) a lodging guest's ID on their stay (ADR 0031). Sensitive PII; not logged. */
    @Transactional
    public OrderResponse setGuestId(String businessId, String orderId, String idType, String idNumber) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order stay = require(businessId, orderId);
        if (!OrderTicketService.CHANNEL_LODGING.equals(stay.channel())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a room stay");
        }
        return toResponse(businessId, orderTicketService.setGuestId(businessId, orderId, idType, idNumber));
    }

    /** A booking's guests have arrived (a table), or a stay's guest checks in (a room). */
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
        // Checking in charges the stay for all its nights at once: one room-night line, the rate
        // frozen onto it now, so a later rate change never moves this folio (ADR 0028). The room is
        // re-checked here, not just at booking: a room taken out of use or out for repair between
        // booking and arrival must not take the guest in (ADR 0030).
        if (OrderTicketService.CHANNEL_LODGING.equals(order.channel())) {
            if (order.tableResourceId() != null) {
                requireRoom(businessId, order.branchId(), order.tableResourceId());
            }
            addRoomNightLine(businessId, order);
        }
        OrderTicketService.Order seated = orderTicketService.seatBooking(businessId, orderId);
        return toResponse(businessId, seated);
    }

    /** Adds the ROOM line(s) for a stay, if none has been added already (check-in is idempotent). */
    private void addRoomNightLine(String businessId, OrderTicketService.Order order) {
        String roomId = order.tableResourceId();
        if (roomId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This stay has no room");
        }
        boolean alreadyCharged = order.lines().stream()
                .anyMatch(l -> "ROOM".equals(l.lineKind()) && !l.isVoided());
        if (alreadyCharged) {
            return;
        }
        ServiceResourceService.RoomRecord room = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "This stay's room is gone"));
        // Resolve the room's rate card to the exact nights now, and freeze each onto its own ROOM
        // line (ADR 0030): one line when every night is the same rate, two when a weekend rate
        // applies to some of them. A later rate or rate-card change never moves this folio (ADR 0028).
        List<ServiceResourceService.RoomNightTier> tiers =
                resourceService.resolveRoomNights(businessId, roomId, order.scheduledStart(), order.scheduledEnd());
        List<OrderTicketService.NewLine> lines = new ArrayList<>();
        for (ServiceResourceService.RoomNightTier tier : tiers) {
            String kind = tier.weekend() && tiers.size() > 1 ? "weekend night" : "night";
            String label = tier.nights() == 1
                    ? room.name() + " — 1 " + kind
                    : room.name() + " — " + tier.nights() + " " + kind + "s";
            lines.add(new OrderTicketService.NewLine("ROOM", null, null, label,
                    java.math.BigDecimal.valueOf(tier.nights()), tier.rate(), null, null, null, false));
        }
        orderTicketService.addLines(businessId, order.id(), lines);
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
    public OrderResponse cancel(String businessId, String orderId, String reason, String userId,
            boolean refundsDeposit, String depositBack, String tillDeviceKey) {
        gate.requireEnabled(businessId);
        orderTicketService.lock(businessId, orderId);
        if (billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId).stream()
                .anyMatch(OrderBill::holdsItsLines)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This order has a bill; it cannot be cancelled");
        }
        return toResponse(businessId, orderTicketService.cancel(businessId, orderId, reason, userId, Instant.now(),
                refundsDeposit, depositBack, tillDeviceKey));
    }

    /**
     * Money taken in advance against a booking. It is held in the customer's name, so the booking
     * must name a customer: when it has only a name and phone, a customer is found by that phone or
     * made from the name and phone, and kept on the booking.
     */
    @Transactional
    public OrderResponse takeDeposit(String businessId, String orderId, BigDecimal amount, String method,
            String userId, String tillDeviceKey) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        if (order.customerId() == null) {
            String customerId = ensureCustomerForDeposit(businessId, order, userId);
            orderTicketService.setDetails(businessId, orderId, null, null, customerId, null, null, null);
        }
        return toResponse(businessId,
                orderTicketService.takeDeposit(businessId, orderId, amount, method, userId, tillDeviceKey));
    }

    /**
     * A deposit paid online by M-Pesa STK, recorded once the gateway confirms the receipt (ADR 0031).
     * The money has already landed in M-Pesa clearing, so there is no drawer and no till; the held
     * deposit is kept in the guest's name exactly as a desk deposit, so release/forfeit/refund all
     * work unchanged. Idempotent: a second confirmation of the same amount is a no-op (the underlying
     * {@code takeDeposit} returns the existing held deposit), so a replayed callback never double-holds.
     */
    @Transactional
    public OrderResponse takeOnlineDeposit(String businessId, String orderId, BigDecimal amount) {
        gate.requireEnabled(businessId);
        OrderTicketService.Order order = orderTicketService.lock(businessId, orderId);
        if (order.customerId() == null) {
            // Online deposits must not hit the match-or-throw path: an ambiguous phone there raises a
            // CONFLICT that would roll back the gateway callback forever (the money is already received).
            // Match an existing customer only when the phone is unambiguous; otherwise make a fresh one
            // from the booking's name and phone, which staff can merge later.
            String customerId = resolveOnlineDepositCustomer(businessId, order);
            orderTicketService.setDetails(businessId, orderId, null, null, customerId, null, null, null);
        }
        return toResponse(businessId, orderTicketService.takeDeposit(businessId, orderId, amount,
                zelisline.ub.sales.SalesConstants.PAYMENT_METHOD_MPESA_STK, ONLINE_DEPOSIT_ACTOR, null));
    }

    private static final String ONLINE_DEPOSIT_ACTOR = "online";

    /**
     * The customer an online deposit is held for. Unlike {@link #ensureCustomerForDeposit}, an
     * ambiguous phone never throws — the online guest gets a fresh record rather than blocking a
     * received payment. A blank phone (should not happen: the booking requires one) also makes a
     * record keyed on the name so the money is never stranded.
     */
    private String resolveOnlineDepositCustomer(String businessId, OrderTicketService.Order order) {
        String phone = blankToNull(order.customerPhone());
        String name = blankToNull(order.customerName());
        if (phone != null) {
            zelisline.ub.credits.application.CustomerDirectoryService.PhoneMatch match =
                    customerDirectoryService.findByPhone(businessId, phone);
            if (!match.ambiguous() && match.customerId() != null) {
                return match.customerId();
            }
        }
        java.util.List<zelisline.ub.credits.api.dto.CustomerPhoneDraft> phones = phone != null
                ? java.util.List.of(new zelisline.ub.credits.api.dto.CustomerPhoneDraft(phone, true))
                : java.util.List.of();
        return customerDirectoryService.create(businessId, new zelisline.ub.credits.api.dto.CreateCustomerRequest(
                name != null ? name : (phone != null ? phone : "Guest"), null, null, null, phones, null)).id();
    }

    /** A booking whose guests never arrived; the deposit is kept as income, or as the customer's credit. */
    @Transactional
    public OrderResponse noShow(String businessId, String orderId, String userId, boolean keepAsCredit) {
        gate.requireEnabled(businessId);
        orderTicketService.lock(businessId, orderId);
        if (billRepository.findByBusinessIdAndOrderIdOrderByBillNoAsc(businessId, orderId).stream()
                .anyMatch(OrderBill::holdsItsLines)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This booking has a bill; it is not a no-show");
        }
        return toResponse(businessId, orderTicketService.noShow(businessId, orderId, userId, keepAsCredit, Instant.now()));
    }

    /**
     * The customer a deposit is held for: found by the booking's phone (an existing customer), or
     * made from the booking's name and phone when none is on file yet.
     */
    private String ensureCustomerForDeposit(String businessId, OrderTicketService.Order order, String userId) {
        String phone = blankToNull(order.customerPhone());
        if (phone == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A deposit needs the customer's phone number; add it to the booking first");
        }
        zelisline.ub.credits.application.CustomerDirectoryService.PhoneMatch match =
                customerDirectoryService.findByPhone(businessId, phone);
        if (match.ambiguous()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That phone matches more than one customer; choose the customer the deposit is for");
        }
        if (match.customerId() != null) {
            return match.customerId();
        }
        String name = blankToNull(order.customerName());
        return customerDirectoryService.create(businessId, new zelisline.ub.credits.api.dto.CreateCustomerRequest(
                name != null ? name : phone, null, null, null,
                java.util.List.of(new zelisline.ub.credits.api.dto.CustomerPhoneDraft(phone, true)), null)).id();
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
                order.riderName(), order.dispatchedAt(), order.deliveredAt(), order.source(),
                order.depositAmount(), order.depositState(), order.guestIdType(), order.guestIdNumber());
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
                total.subtract(settledValue).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP),
                order.source());
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

    private void requireRoom(String businessId, String branchId, String roomId) {
        ServiceResourceService.RoomRecord room = resourceService.room(businessId, roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Room not found"));
        if (!room.branchId().equals(branchId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That room is at another branch");
        }
        if (!room.active()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, room.name() + " is not in use");
        }
        // A room out for repair takes no stay, however its dates look (ADR 0030). Clean/dirty
        // turnaround is a same-day matter left to the staff, not a booking-time block.
        if (ServiceResourceService.HOUSEKEEPING_MAINTENANCE.equals(room.housekeepingStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, room.name() + " is out for repair");
        }
    }

    /**
     * Two stays cannot hold one room over the same nights. Unlike a table, a seated (checked-in)
     * stay still holds its room, so the room-specific overlap query counts in-house stays too — a
     * guest can never be moved or booked onto a room another guest is in (ADR 0030).
     */
    private void requireRoomFreeForStay(
            String businessId, String branchId, String roomId, Instant from, Instant to, String exceptOrderId) {
        for (OrderTicketService.Order other : orderTicketService.roomStaysOverlapping(businessId, branchId, from, to)) {
            if (roomId.equals(other.tableResourceId()) && !other.id().equals(exceptOrderId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "That room is taken for those nights");
            }
        }
    }

    private void requireStaff(String businessId, String staffProfileId) {
        if (staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId)).get(staffProfileId) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Staff member not found");
        }
    }

    /** A staff member of this business must exist for the given id — for naming a room's cleaner. */
    public void requireStaffMember(String businessId, String staffProfileId) {
        requireStaff(businessId, staffProfileId);
    }

    /** The display name of a staff profile, or null when none or since left — for a room's cleaner. */
    @Transactional(readOnly = true)
    public String staffName(String businessId, String staffProfileId) {
        if (staffProfileId == null || staffProfileId.isBlank()) {
            return null;
        }
        return staffProfileService.displayNamesByIds(businessId, List.of(staffProfileId)).get(staffProfileId);
    }

    /** The names of the waiters assigned to these tables, by staff profile id. */
    private Map<String, String> waiterNames(String businessId, List<ServiceResourceService.TableRecord> tables) {
        List<String> ids = tables.stream().map(ServiceResourceService.TableRecord::assignedStaffProfileId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        return ids.isEmpty() ? Map.of() : staffProfileService.displayNamesByIds(businessId, ids);
    }

    private TableResponse toResponse(String businessId, ServiceResourceService.TableRecord table) {
        return toResponse(table, waiterNames(businessId, List.of(table)));
    }

    private static TableResponse toResponse(ServiceResourceService.TableRecord table, Map<String, String> waiters) {
        // A waiter who has since left the team is no longer shown on the table.
        String name = table.assignedStaffProfileId() == null ? null : waiters.get(table.assignedStaffProfileId());
        return new TableResponse(table.id(), table.name(), table.capacity(), table.area(), table.active(),
                table.sortOrder(), table.qrToken(), name == null ? null : table.assignedStaffProfileId(), name);
    }

    private static String blankToNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw.trim();
    }
}
