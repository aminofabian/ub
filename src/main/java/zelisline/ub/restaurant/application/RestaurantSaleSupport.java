package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.realtime.RealtimeBridge;
import zelisline.ub.posdraft.application.OrderTicketService;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;
import zelisline.ub.restaurant.domain.KitchenTicket;
import zelisline.ub.restaurant.domain.KitchenTicketLine;
import zelisline.ub.restaurant.domain.MenuCategory;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.domain.MenuItemModifierGroup;
import zelisline.ub.restaurant.domain.ModifierGroup;
import zelisline.ub.restaurant.domain.ModifierOption;
import zelisline.ub.restaurant.repository.KitchenTicketLineRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;
import zelisline.ub.restaurant.repository.MenuCategoryRepository;
import zelisline.ub.restaurant.repository.MenuItemModifierGroupRepository;
import zelisline.ub.restaurant.repository.MenuItemRepository;
import zelisline.ub.restaurant.repository.ModifierGroupRepository;
import zelisline.ub.restaurant.repository.ModifierOptionRepository;
import zelisline.ub.sales.api.dto.PostSaleLineRequest;

/**
 * What the sale path needs from the restaurant menu: the price and snapshot of a dish line, and,
 * once a sale is paid, the order and the kitchen tickets that go with it (ADR 0020, phase 1a:
 * the customer pays first, so one order is exactly one sale).
 */
@Service
@RequiredArgsConstructor
public class RestaurantSaleSupport {

    static final String TICKET_NEW = "NEW";
    static final String TICKET_VOID = "VOID";
    static final String STATUS_OPEN = "OPEN";
    static final String STATUS_READY = "READY";
    static final String STATUS_SERVED = "SERVED";
    static final String STATUS_VOIDED = "VOIDED";

    private static final int MAX_SNAPSHOT_CHARS = 2000;

    private final MenuItemRepository menuItemRepository;
    private final MenuCategoryRepository categoryRepository;
    private final ModifierGroupRepository groupRepository;
    private final ModifierOptionRepository optionRepository;
    private final MenuItemModifierGroupRepository linkRepository;
    private final KitchenTicketRepository ticketRepository;
    private final KitchenTicketLineRepository ticketLineRepository;
    private final OrderTicketService orderTicketService;
    private final RecipeService recipeService;
    private final zelisline.ub.restaurant.repository.OrderBillRepository billRepository;
    private final RestaurantGate gate;
    private final ApplicationEventPublisher eventPublisher;

    /** A dish as it is sold: its name, its price with the chosen modifiers, and what was chosen. */
    public record DishLine(
            String menuItemId, String name, BigDecimal unitPrice, String modifiersJson, String taxRateId,
            /** The name with what was chosen on it, as it should read on a receipt. */
            String label) {
    }

    /**
     * Prices a dish. This is the only place a dish gets its price; the amount a client sends is
     * checked against it, never trusted.
     *
     * @throws ResponseStatusException 400 when the dish is unknown, off the menu or run out, or
     *         the chosen options do not fit the dish's modifier groups
     */
    @Transactional(readOnly = true)
    public DishLine priceDish(String businessId, String menuItemId, List<String> optionIds) {
        return priceDish(businessId, menuItemId, optionIds, false);
    }

    /**
     * @param alreadySold true for a sale that was made while the till was offline and is only now
     *        reaching the server. The customer has paid and eaten, so a dish or option that has
     *        since run out or been switched off is still accepted. A dish removed from the menu,
     *        or choices that never fitted it, are still refused.
     */
    @Transactional(readOnly = true)
    public DishLine priceDish(String businessId, String menuItemId, List<String> optionIds, boolean alreadySold) {
        gate.requireEnabled(businessId);
        if (menuItemId == null || menuItemId.isBlank()) {
            throw bad("Choose a dish from the menu");
        }
        MenuItem item = menuItemRepository.findByIdAndBusinessId(menuItemId.trim(), businessId)
                .filter(i -> i.getDeletedAt() == null)
                .orElseThrow(() -> bad("That dish is no longer on the menu"));
        if (!MenuService.KIND_DISH.equals(item.getKind())) {
            throw bad(item.getName() + " is a product, not a dish");
        }
        if (!alreadySold && !item.isActive()) {
            throw bad(item.getName() + " is not on the menu at the moment");
        }
        if (!alreadySold && !item.isAvailable()) {
            throw bad(item.getName() + " has run out");
        }
        if (item.getPrice() == null || item.getPrice().signum() <= 0) {
            throw bad(item.getName() + " has no price");
        }
        MenuClock.At at = MenuClock.At.now();
        if (!alreadySold && !MenuClock.served(item, at)) {
            throw bad(item.getName() + " is not served at this time of day");
        }

        List<MenuItemModifierGroup> links =
                linkRepository.findByBusinessIdAndMenuItemIdOrderBySortOrderAsc(businessId, item.getId());
        Map<String, ModifierGroup> groups = new LinkedHashMap<>();
        for (MenuItemModifierGroup link : links) {
            groupRepository.findByIdAndBusinessId(link.getGroupId(), businessId)
                    .filter(g -> g.getDeletedAt() == null)
                    .ifPresent(g -> groups.put(g.getId(), g));
        }
        Map<String, ModifierOption> options = new HashMap<>();
        for (String groupId : groups.keySet()) {
            for (ModifierOption option : optionRepository
                    .findByBusinessIdAndGroupIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId, groupId)) {
                options.put(option.getId(), option);
            }
        }

        Map<String, List<ModifierOption>> chosenByGroup = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (String raw : optionIds == null ? List.<String>of() : optionIds) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            ModifierOption option = options.get(raw.trim());
            if (option == null) {
                throw bad("One of the choices does not belong to " + item.getName());
            }
            if (!seen.add(option.getId())) {
                throw bad(option.getName() + " was chosen twice");
            }
            if (!alreadySold && !option.isAvailable()) {
                throw bad(option.getName() + " has run out");
            }
            chosenByGroup.computeIfAbsent(option.getGroupId(), k -> new ArrayList<>()).add(option);
        }

        // Happy hour changes the dish's own price; what its choices add stays the same.
        BigDecimal price = MenuClock.price(item, at);
        List<ChosenModifier> snapshot = new ArrayList<>();
        for (ModifierGroup group : groups.values()) {
            List<ModifierOption> chosen = chosenByGroup.getOrDefault(group.getId(), List.of());
            if (chosen.size() < group.getMinSelect()) {
                throw bad(group.getMinSelect() == 1
                        ? "Choose " + group.getName() + " for " + item.getName()
                        : "Choose at least " + group.getMinSelect() + " of " + group.getName());
            }
            if (chosen.size() > group.getMaxSelect()) {
                throw bad("Choose at most " + group.getMaxSelect() + " of " + group.getName());
            }
            chosen.stream()
                    .sorted(java.util.Comparator.comparingInt(ModifierOption::getSortOrder)
                            .thenComparing(ModifierOption::getName))
                    .forEach(o -> snapshot.add(
                            new ChosenModifier(group.getName(), o.getName(), o.getPriceDelta(), o.getId())));
            for (ModifierOption option : chosen) {
                price = price.add(option.getPriceDelta());
            }
        }
        String json = ModifierSnapshots.encode(snapshot);
        if (json != null && json.length() > MAX_SNAPSHOT_CHARS) {
            throw bad("Too many choices on one line");
        }
        String label = item.getName();
        if (!snapshot.isEmpty()) {
            label = label + " (" + String.join(", ", snapshot.stream().map(ChosenModifier::option).toList()) + ")";
        }
        if (label.length() > 255) {
            label = label.substring(0, 252) + "...";
        }
        return new DishLine(item.getId(), item.getName(), price.setScale(2, RoundingMode.HALF_UP), json,
                item.getTaxRateId(), label);
    }

    /**
     * Takes a sold dish's ingredients off the stock count and returns what they cost; zero when
     * the dish has no recipe.
     */
    @Transactional
    public BigDecimal useIngredients(
            String businessId, String branchId, String saleId, String userId, DishLine dish, BigDecimal quantity) {
        return recipeService.useIngredients(businessId, branchId, saleId, userId, dish.menuItemId(),
                ModifierSnapshots.decode(dish.modifiersJson()), quantity);
    }

    /**
     * Writes the order and its kitchen tickets for a sale that has just been paid, in the sale's
     * transaction: either the sale and what the kitchen was told both exist, or neither does.
     * A sale with nothing for a kitchen (shelf goods only) writes nothing.
     *
     * <p>Dishes always go to a kitchen: the station on the dish, else the one on its heading,
     * else the business's one implicit kitchen. A product rung from the menu goes only if a
     * station is named for it; a bottled soda is handed over at the counter.
     */
    @Transactional
    public void recordOrderForSale(
            String businessId, String branchId, String userId, String shiftId, String saleId,
            String customerId, List<PostSaleLineRequest> lines, Instant soldAt, boolean alreadySold) {
        List<OrderTicketService.NewLine> orderLines = new ArrayList<>();
        boolean anyForKitchen = false;
        Map<String, MenuCategory> categories = new HashMap<>();
        for (PostSaleLineRequest line : lines) {
            if (line.menuItemId() == null || line.menuItemId().isBlank() || line.isAirtime() || line.isService()) {
                continue;
            }
            MenuItem item = menuItemRepository.findByIdAndBusinessId(line.menuItemId().trim(), businessId).orElse(null);
            if (item == null) {
                continue;
            }
            String stationId = item.getStationId();
            if (stationId == null && item.getCategoryId() != null) {
                MenuCategory category = categories.computeIfAbsent(item.getCategoryId(),
                        id -> categoryRepository.findByIdAndBusinessId(id, businessId).orElse(null));
                stationId = category == null ? null : category.getStationId();
            }
            if (line.isDish()) {
                DishLine dish = priceDish(businessId, item.getId(), line.modifierOptionIds(), alreadySold);
                orderLines.add(new OrderTicketService.NewLine("DISH", null, item.getId(), dish.name(),
                        line.quantity(), alreadySold ? line.unitPrice() : dish.unitPrice(), dish.modifiersJson(), trimNote(line.note()), stationId, true));
                anyForKitchen = true;
            } else if (MenuService.KIND_ITEM.equals(item.getKind()) && item.getItemId() != null
                    && item.getItemId().equals(line.itemId())) {
                boolean toKitchen = stationId != null;
                orderLines.add(new OrderTicketService.NewLine("ITEM", item.getItemId(), item.getId(), item.getName(),
                        line.quantity(), line.unitPrice(), null, trimNote(line.note()), stationId, toKitchen));
                anyForKitchen |= toKitchen;
            }
        }
        if (!anyForKitchen) {
            return;
        }
        OrderTicketService.Order order = orderTicketService.createPaidCounterOrder(
                businessId, branchId, userId, shiftId, saleId, customerId, orderLines, soldAt);
        if (!ticketRepository.findByBusinessIdAndSaleId(businessId, saleId).isEmpty()) {
            return; // already sent: a replayed sale tells the kitchen nothing new
        }
        Instant now = soldAt != null ? soldAt : Instant.now();
        Map<String, List<OrderTicketService.OrderLine>> byStation = new LinkedHashMap<>();
        for (OrderTicketService.OrderLine line : order.lines()) {
            if (OrderTicketService.STAGE_NEW.equals(line.stage())) {
                byStation.computeIfAbsent(line.stationId() == null ? "" : line.stationId(), k -> new ArrayList<>())
                        .add(line);
            }
        }
        for (Map.Entry<String, List<OrderTicketService.OrderLine>> entry : byStation.entrySet()) {
            writeTicket(businessId, order, entry.getKey().isEmpty() ? null : entry.getKey(), TICKET_NEW,
                    STATUS_OPEN, userId, now, entry.getValue(), 1);
        }
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, branchId));
    }

    /**
     * Tells the kitchen about lines just sent from an open order: one ticket per station, in the
     * next round. Lines that need no making (stage {@code READY}) are left out.
     */
    @Transactional
    public void sendRound(String businessId, OrderTicketService.Order order, List<OrderTicketService.OrderLine> sent,
            String userId, Instant now) {
        Map<String, List<OrderTicketService.OrderLine>> byStation = new LinkedHashMap<>();
        for (OrderTicketService.OrderLine line : sent) {
            if (OrderTicketService.STAGE_NEW.equals(line.stage())) {
                byStation.computeIfAbsent(line.stationId() == null ? "" : line.stationId(), k -> new ArrayList<>())
                        .add(line);
            }
        }
        if (byStation.isEmpty()) {
            return;
        }
        int round = ticketRepository.findByBusinessIdAndOrderId(businessId, order.id()).stream()
                .filter(t -> TICKET_NEW.equals(t.getTicketType()))
                .mapToInt(KitchenTicket::getRoundNo).max().orElse(0) + 1;
        for (Map.Entry<String, List<OrderTicketService.OrderLine>> entry : byStation.entrySet()) {
            writeTicket(businessId, order, entry.getKey().isEmpty() ? null : entry.getKey(), TICKET_NEW,
                    STATUS_OPEN, userId, now, entry.getValue(), round);
        }
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, order.branchId()));
    }

    /**
     * A sent line was cancelled on an open order. If the kitchen was still making it, its station
     * gets a void ticket; either way the tickets it was on stop waiting for it.
     */
    @Transactional
    public void lineCancelled(String businessId, OrderTicketService.Order order, OrderTicketService.OrderLine line,
            boolean kitchenWasMakingIt, String userId, Instant now) {
        if (kitchenWasMakingIt) {
            writeTicket(businessId, order, line.stationId(), TICKET_VOID, STATUS_VOIDED, userId, now, List.of(line), 1);
        }
        refreshTickets(businessId, order.id(), now);
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, order.branchId()));
    }

    /** Two orders were joined: the kitchen's tickets for the emptied one now belong to the other. */
    @Transactional
    public void ordersJoined(String businessId, String fromOrderId, OrderTicketService.Order into) {
        for (KitchenTicket ticket : ticketRepository.findByBusinessIdAndOrderId(businessId, fromOrderId)) {
            ticket.setOrderId(into.id());
            ticket.setOrderNumber(into.orderNumber());
            ticketRepository.save(ticket);
        }
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, into.branchId()));
    }

    /**
     * The order number to print on a sale's receipt: the counter order it produced, or the table's
     * order whose bill it paid. Empty for a sale that has nothing to do with the kitchen.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Long> orderNumberForSale(String businessId, String saleId) {
        java.util.Optional<zelisline.ub.restaurant.domain.OrderBill> bill =
                billRepository.findFirstByBusinessIdAndSaleId(businessId, saleId);
        if (bill.isPresent()) {
            return orderTicketService.find(businessId, bill.get().getOrderId())
                    .map(OrderTicketService.Order::orderNumber);
        }
        return orderTicketService.findBySale(businessId, saleId).map(OrderTicketService.Order::orderNumber);
    }

    /** Works every ticket of an order's status out again from where its lines stand now. */
    @Transactional
    public void refreshTickets(String businessId, String orderId, Instant now) {
        Map<String, OrderTicketService.OrderLine> current = new HashMap<>();
        for (OrderTicketService.OrderLine line : orderTicketService.find(businessId, orderId).orElseThrow().lines()) {
            current.put(line.id(), line);
        }
        List<KitchenTicket> tickets = ticketRepository.findByBusinessIdAndOrderId(businessId, orderId);
        Map<String, List<KitchenTicketLine>> linesByTicket = new HashMap<>();
        for (KitchenTicketLine line : ticketLineRepository.findByBusinessIdAndTicketIdInOrderByLineIndexAsc(
                businessId, tickets.stream().map(KitchenTicket::getId).toList())) {
            linesByTicket.computeIfAbsent(line.getTicketId(), k -> new ArrayList<>()).add(line);
        }
        for (KitchenTicket ticket : tickets) {
            if (TICKET_NEW.equals(ticket.getTicketType())) {
                List<String> stages = linesByTicket.getOrDefault(ticket.getId(), List.of()).stream()
                        .map(l -> current.get(l.getOrderLineId()))
                        .filter(java.util.Objects::nonNull)
                        .map(OrderTicketService.OrderLine::stage)
                        .toList();
                applyStatus(ticket, stages, now);
                ticketRepository.save(ticket);
            }
        }
    }

    /**
     * A sale was voided. Anything the kitchen had not finished is cancelled and each station that
     * was making it gets a void ticket, so nobody keeps cooking a meal that will not be collected.
     */
    @Transactional
    public void onSaleVoided(String businessId, String branchId, String saleId, String userId) {
        // Whatever the dishes on the sale took from the stock count goes back with the void.
        recipeService.restoreForVoidedSale(businessId, branchId, saleId, userId);
        // The sale of a bill on a table's order: the food stays as it is, but the bill is no longer
        // paid, so its lines are unsettled again and the order is open again.
        zelisline.ub.restaurant.domain.OrderBill found = billRepository.findFirstByBusinessIdAndSaleId(businessId, saleId)
                .orElse(null);
        if (found != null) {
            // Take the order's row lock before touching the bill, so this void serialises with any
            // concurrent pay() or split on the same order (both lock the order first). Flipping the
            // bill's status changes whether its lines count as billed, so it must not happen while
            // another request is reading that to build a new bill. Re-read the bill under the lock.
            orderTicketService.lock(businessId, found.getOrderId());
            zelisline.ub.restaurant.domain.OrderBill bill =
                    billRepository.findFirstByBusinessIdAndSaleId(businessId, saleId).orElse(found);
            if (zelisline.ub.restaurant.domain.OrderBill.PAID.equals(bill.getStatus())) {
                bill.setStatus(zelisline.ub.restaurant.domain.OrderBill.VOIDED);
                bill.setReason("Sale voided");
                billRepository.save(bill);
                orderTicketService.reopen(businessId, bill.getOrderId());
            }
            return;
        }
        OrderTicketService.Order order = orderTicketService.findBySale(businessId, saleId).orElse(null);
        if (order == null) {
            return;
        }
        List<OrderTicketService.OrderLine> voided = orderTicketService.voidUnfinishedLines(businessId, order.id());
        if (voided.isEmpty()) {
            return;
        }
        Instant now = Instant.now();
        Map<String, List<OrderTicketService.OrderLine>> byStation = new LinkedHashMap<>();
        for (OrderTicketService.OrderLine line : voided) {
            byStation.computeIfAbsent(line.stationId() == null ? "" : line.stationId(), k -> new ArrayList<>()).add(line);
        }
        for (Map.Entry<String, List<OrderTicketService.OrderLine>> entry : byStation.entrySet()) {
            writeTicket(businessId, order, entry.getKey().isEmpty() ? null : entry.getKey(), TICKET_VOID,
                    STATUS_VOIDED, userId, now, entry.getValue(), 1);
        }
        // The tickets those lines were on are no longer waiting for them.
        refreshTickets(businessId, order.id(), now);
        eventPublisher.publishEvent(new RealtimeBridge.KitchenUpdatedEvent(businessId, order.branchId()));
    }

    /** Sets a ticket's status from the stages of its lines. A ticket whose lines were all cancelled is voided. */
    static void applyStatus(KitchenTicket ticket, List<String> lineStages, Instant now) {
        List<String> live = lineStages.stream().filter(s -> !OrderTicketService.STAGE_VOIDED.equals(s)).toList();
        if (live.isEmpty()) {
            ticket.setStatus(STATUS_VOIDED);
            return;
        }
        boolean allServed = live.stream().allMatch(OrderTicketService.STAGE_SERVED::equals);
        boolean allMade = live.stream().allMatch(
                s -> OrderTicketService.STAGE_READY.equals(s) || OrderTicketService.STAGE_SERVED.equals(s));
        if (allMade) {
            if (ticket.getReadyAt() == null) {
                ticket.setReadyAt(now);
            }
            ticket.setStatus(allServed ? STATUS_SERVED : STATUS_READY);
            ticket.setServedAt(allServed ? (ticket.getServedAt() == null ? now : ticket.getServedAt()) : null);
        } else {
            ticket.setStatus(STATUS_OPEN);
            ticket.setReadyAt(null);
            ticket.setServedAt(null);
        }
    }

    private void writeTicket(
            String businessId, OrderTicketService.Order order, String stationId, String type, String status,
            String userId, Instant now, List<OrderTicketService.OrderLine> lines, int roundNo) {
        KitchenTicket ticket = new KitchenTicket();
        ticket.setRoundNo(roundNo);
        ticket.setBusinessId(businessId);
        ticket.setBranchId(order.branchId());
        ticket.setOrderId(order.id());
        ticket.setOrderNumber(order.orderNumber());
        ticket.setSaleId(order.saleId());
        ticket.setStationId(stationId);
        ticket.setTicketType(type);
        ticket.setStatus(status);
        ticket.setSentBy(userId);
        ticket.setSentAt(now);
        ticketRepository.save(ticket);
        int index = 0;
        for (OrderTicketService.OrderLine line : lines) {
            KitchenTicketLine row = new KitchenTicketLine();
            row.setBusinessId(businessId);
            row.setTicketId(ticket.getId());
            row.setOrderLineId(line.id());
            row.setLineIndex(index++);
            row.setName(line.name().length() > 191 ? line.name().substring(0, 191) : line.name());
            row.setQuantity(line.quantity());
            row.setModifiers(line.modifiers());
            row.setNote(line.note());
            ticketLineRepository.save(row);
        }
    }

    private static String trimNote(String note) {
        return OrderTicketService.cleanNote(note);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
