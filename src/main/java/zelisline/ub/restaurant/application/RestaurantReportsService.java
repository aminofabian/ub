package zelisline.ub.restaurant.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ChosenModifier;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.KitchenTimesReport;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuItemSalesRow;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.MenuSalesReport;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.ModifierSalesRow;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.StationTimesRow;
import zelisline.ub.restaurant.domain.KitchenStation;
import zelisline.ub.restaurant.domain.KitchenTicket;
import zelisline.ub.restaurant.repository.KitchenStationRepository;
import zelisline.ub.restaurant.repository.KitchenTicketRepository;

/**
 * What sold from the menu and how long the kitchen took. Read-only. Days are Nairobi days, each
 * starting at the hour the restaurant has set as the start of its day (midnight unless it says
 * otherwise), so a bar's sales after midnight stay with the night they belong to.
 * Voided sales are left out; refunds are not subtracted (a refunded dish still counts as sold).
 * A dish has a cost, a margin and a menu-engineering class only if it has a recipe.
 */
@Service
@RequiredArgsConstructor
public class RestaurantReportsService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int MAX_DAYS = 92;
    /** Lines read to count modifier choices; beyond this the modifier table is a sample and says nothing wrong, only less. */
    private static final int MAX_MODIFIER_ROWS = 20000;

    private final KitchenTicketRepository ticketRepository;
    private final KitchenStationRepository stationRepository;
    private final RestaurantGate gate;
    private final RecipeService recipeService;
    private final RestaurantSettingsService settingsService;
    private final zelisline.ub.serviceops.application.ServiceResourceService resourceService;
    private final zelisline.ub.payroll.application.StaffProfileService staffProfileService;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public MenuSalesReport menuSales(String businessId, String branchId, LocalDate from, LocalDate to) {
        gate.requireEnabled(businessId);
        int dayStart = settingsService.get(businessId).getDayStartsAtMinute();
        Instant start = start(from, to).plusSeconds(dayStart * 60L);
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant().plusSeconds(dayStart * 60L);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT si.menu_item_id, MAX(si.display_name), SUM(si.quantity), SUM(si.line_total), SUM(si.cost_total)
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                         WHERE s.business_id = :businessId
                           AND s.branch_id = :branchId
                           AND s.status <> 'voided'
                           AND si.line_kind = 'DISH'
                           AND s.sold_at >= :fromTs AND s.sold_at < :toTs
                         GROUP BY si.menu_item_id
                        """)
                .setParameter("businessId", businessId)
                .setParameter("branchId", branchId)
                .setParameter("fromTs", start)
                .setParameter("toTs", end)
                .getResultList();
        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : rows) {
            total = total.add(decimal(row[3]));
        }
        // Menu engineering compares each dish with the others: does it sell at least 70% of an even
        // share, and does one plate earn at least the average plate. Only dishes with a recipe are
        // compared, because only they have a cost.
        java.util.Set<String> withRecipe = recipeService.dishesWithRecipe(businessId);
        BigDecimal costedQuantity = BigDecimal.ZERO;
        BigDecimal costedMargin = BigDecimal.ZERO;
        BigDecimal totalCost = BigDecimal.ZERO;
        int costedDishes = 0;
        for (Object[] row : rows) {
            if (row[0] != null && withRecipe.contains(row[0])) {
                costedDishes++;
                costedQuantity = costedQuantity.add(decimal(row[2]));
                costedMargin = costedMargin.add(decimal(row[3]).subtract(decimal(row[4])));
                totalCost = totalCost.add(decimal(row[4]));
            }
        }
        BigDecimal popularAt = costedDishes == 0 ? BigDecimal.ZERO
                : costedQuantity.multiply(new BigDecimal("0.7")).divide(BigDecimal.valueOf(costedDishes), 4, RoundingMode.HALF_UP);
        BigDecimal averageMargin = costedQuantity.signum() == 0 ? BigDecimal.ZERO
                : costedMargin.divide(costedQuantity, 4, RoundingMode.HALF_UP);
        List<MenuItemSalesRow> items = new ArrayList<>();
        for (Object[] row : rows) {
            BigDecimal quantity = decimal(row[2]);
            BigDecimal revenue = decimal(row[3]).setScale(2, RoundingMode.HALF_UP);
            BigDecimal share = total.signum() == 0 ? BigDecimal.ZERO
                    : revenue.multiply(BigDecimal.valueOf(100)).divide(total, 1, RoundingMode.HALF_UP);
            BigDecimal cost = null;
            BigDecimal margin = null;
            BigDecimal costPercent = null;
            String engineering = null;
            if (row[0] != null && withRecipe.contains(row[0])) {
                cost = decimal(row[4]).setScale(2, RoundingMode.HALF_UP);
                margin = revenue.subtract(cost);
                costPercent = revenue.signum() == 0 ? null
                        : cost.multiply(BigDecimal.valueOf(100)).divide(revenue, 1, RoundingMode.HALF_UP);
                boolean popular = quantity.compareTo(popularAt) >= 0;
                boolean earns = quantity.signum() > 0
                        && margin.divide(quantity, 4, RoundingMode.HALF_UP).compareTo(averageMargin) >= 0;
                engineering = popular ? (earns ? "STAR" : "PLOWHORSE") : (earns ? "PUZZLE" : "DOG");
            }
            items.add(new MenuItemSalesRow((String) row[0], (String) row[1], quantity.stripTrailingZeros(), revenue,
                    share, cost, margin, costPercent, engineering));
        }
        items.sort(Comparator.comparing(MenuItemSalesRow::revenue).reversed());

        @SuppressWarnings("unchecked")
        List<Object[]> modifierRows = entityManager.createNativeQuery("""
                        SELECT si.modifiers, si.quantity
                          FROM sale_items si
                          JOIN sales s ON s.id = si.sale_id
                         WHERE s.business_id = :businessId
                           AND s.branch_id = :branchId
                           AND s.status <> 'voided'
                           AND si.line_kind = 'DISH'
                           AND si.modifiers IS NOT NULL
                           AND s.sold_at >= :fromTs AND s.sold_at < :toTs
                        """)
                .setParameter("businessId", businessId)
                .setParameter("branchId", branchId)
                .setParameter("fromTs", start)
                .setParameter("toTs", end)
                .setMaxResults(MAX_MODIFIER_ROWS)
                .getResultList();
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, BigDecimal> extra = new HashMap<>();
        Map<String, ChosenModifier> sample = new HashMap<>();
        for (Object[] row : modifierRows) {
            BigDecimal quantity = decimal(row[1]);
            for (ChosenModifier chosen : ModifierSnapshots.decode((String) row[0])) {
                String key = chosen.group() + "\u0000" + chosen.option();
                counts.computeIfAbsent(key, k -> new long[1])[0] += quantity.longValue();
                extra.merge(key, (chosen.priceDelta() == null ? BigDecimal.ZERO : chosen.priceDelta()).multiply(quantity),
                        BigDecimal::add);
                sample.putIfAbsent(key, chosen);
            }
        }
        List<ModifierSalesRow> modifiers = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            ChosenModifier chosen = sample.get(entry.getKey());
            modifiers.add(new ModifierSalesRow(chosen.group(), chosen.option(), entry.getValue()[0],
                    extra.get(entry.getKey()).setScale(2, RoundingMode.HALF_UP)));
        }
        modifiers.sort(Comparator.comparingLong(ModifierSalesRow::timesChosen).reversed());
        return new MenuSalesReport(from.toString(), to.toString(), total.setScale(2, RoundingMode.HALF_UP),
                recipeService.anyRecipe(businessId), totalCost.setScale(2, RoundingMode.HALF_UP), items, modifiers);
    }

    /** From sent to ready, per station: how many tickets, the middle one, and the slowest. */
    @Transactional(readOnly = true)
    public KitchenTimesReport kitchenTimes(String businessId, String branchId, LocalDate from, LocalDate to) {
        gate.requireEnabled(businessId);
        int dayStart = settingsService.get(businessId).getDayStartsAtMinute();
        Instant start = start(from, to).plusSeconds(dayStart * 60L);
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant().plusSeconds(dayStart * 60L);
        Map<String, List<Long>> seconds = new LinkedHashMap<>();
        for (KitchenTicket ticket : ticketRepository.findReadyBetween(businessId, branchId, start, end)) {
            long took = Math.max(0, Duration.between(ticket.getSentAt(), ticket.getReadyAt()).getSeconds());
            seconds.computeIfAbsent(ticket.getStationId() == null ? "" : ticket.getStationId(), k -> new ArrayList<>())
                    .add(took);
        }
        Map<String, String> names = new HashMap<>();
        for (KitchenStation station : stationRepository.findByBusinessIdOrderBySortOrderAscNameAsc(businessId)) {
            names.put(station.getId(), station.getName());
        }
        List<StationTimesRow> out = new ArrayList<>();
        for (Map.Entry<String, List<Long>> entry : seconds.entrySet()) {
            List<Long> sorted = entry.getValue().stream().sorted().toList();
            long median = sorted.get((sorted.size() - 1) / 2);
            String id = entry.getKey().isEmpty() ? null : entry.getKey();
            out.add(new StationTimesRow(id, id == null ? "Kitchen" : names.getOrDefault(id, "Station"),
                    sorted.size(), median, sorted.get(sorted.size() - 1)));
        }
        return new KitchenTimesReport(from.toString(), to.toString(), out);
    }

    // ------------------------------------------------------------------ takings by table

    /** One order's paid bills in the period. */
    private record OrderTakings(
            String tableId, String servedBy, long guests, String channel, long bills, BigDecimal taken, BigDecimal tips) {
    }

    private static final class Tally {
        long orders;
        long guests;
        long bills;
        BigDecimal taken = BigDecimal.ZERO;
        BigDecimal tips = BigDecimal.ZERO;

        void add(OrderTakings order) {
            orders++;
            guests += order.guests();
            bills += order.bills();
            taken = taken.add(order.taken());
            tips = tips.add(order.tips());
        }
    }

    /**
     * What each table and each waiter took over a period (ADR 0027). A table's takings are the
     * paid bills of the orders that sat at it; a waiter's are those of the orders they served.
     */
    @Transactional(readOnly = true)
    public zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsReport tableTakings(
            String businessId, String branchId, LocalDate from, LocalDate to) {
        gate.requireEnabled(businessId);
        int dayStart = settingsService.get(businessId).getDayStartsAtMinute();
        Instant start = start(from, to).plusSeconds(dayStart * 60L);
        Instant end = to.plusDays(1).atStartOfDay(NAIROBI).toInstant().plusSeconds(dayStart * 60L);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT d.id, d.table_resource_id, d.served_by_staff_profile_id, d.guest_count, d.order_channel,
                               COUNT(b.id), SUM(s.grand_total), SUM(s.tip_total)
                          FROM order_bills b
                          JOIN sales s ON s.id = b.sale_id
                          JOIN pos_drafts d ON d.id = b.order_id
                         WHERE b.business_id = :businessId
                           AND b.branch_id = :branchId
                           AND d.business_id = :businessId
                           AND s.business_id = :businessId
                           AND b.status = 'PAID'
                           AND s.status <> 'voided'
                           AND s.sold_at >= :fromTs AND s.sold_at < :toTs
                         GROUP BY d.id, d.table_resource_id, d.served_by_staff_profile_id, d.guest_count, d.order_channel
                        """)
                .setParameter("businessId", businessId)
                .setParameter("branchId", branchId)
                .setParameter("fromTs", start)
                .setParameter("toTs", end)
                .getResultList();

        Map<String, Tally> byTable = new HashMap<>();
        Map<String, Tally> byChannel = new java.util.LinkedHashMap<>();
        Map<String, Tally> byWaiter = new HashMap<>();
        Tally all = new Tally();
        for (Object[] row : rows) {
            OrderTakings order = new OrderTakings((String) row[1], (String) row[2],
                    row[3] == null ? 0 : ((Number) row[3]).longValue(), (String) row[4],
                    ((Number) row[5]).longValue(), decimal(row[6]), decimal(row[7]));
            all.add(order);
            if (order.tableId() != null) {
                byTable.computeIfAbsent(order.tableId(), k -> new Tally()).add(order);
            } else {
                byChannel.computeIfAbsent(order.channel() == null ? "" : order.channel(), k -> new Tally()).add(order);
            }
            if (order.servedBy() != null) {
                byWaiter.computeIfAbsent(order.servedBy(), k -> new Tally()).add(order);
            }
        }

        List<zelisline.ub.serviceops.application.ServiceResourceService.TableRecord> tables =
                resourceService.tables(businessId, branchId);
        java.util.Set<String> staffIds = new java.util.HashSet<>(byWaiter.keySet());
        tables.forEach(t -> {
            if (t.assignedStaffProfileId() != null) {
                staffIds.add(t.assignedStaffProfileId());
            }
        });
        Map<String, String> names = staffIds.isEmpty() ? Map.of()
                : staffProfileService.displayNamesByIds(businessId, staffIds);

        List<zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow> tableRows = new ArrayList<>();
        for (var table : tables) {
            Tally tally = byTable.remove(table.id());
            if (tally == null && !table.active()) {
                continue; // out of use and took nothing: not worth a line
            }
            tableRows.add(takingsRow(table.id(), table.name(), table.area(),
                    table.assignedStaffProfileId() == null ? null : names.get(table.assignedStaffProfileId()),
                    tally == null ? new Tally() : tally));
        }
        // A table removed since still took what it took.
        for (Tally orphan : byTable.values()) {
            tableRows.add(takingsRow(null, "A table since removed", null, null, orphan));
        }
        tableRows.sort(Comparator.comparing(zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow::taken).reversed()
                .thenComparing(zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow::name, String.CASE_INSENSITIVE_ORDER));

        List<zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow> offFloor = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : byChannel.entrySet()) {
            String label = switch (entry.getKey()) {
                case "TAKEAWAY" -> "Takeaway";
                case "DELIVERY" -> "Delivery";
                case "DINE_IN" -> "Tabs";
                default -> "Other orders";
            };
            offFloor.add(takingsRow(null, label, null, null, entry.getValue()));
        }
        offFloor.sort(Comparator.comparing(zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow::taken).reversed());

        List<zelisline.ub.restaurant.api.dto.RestaurantDtos.WaiterTakingsRow> waiters = new ArrayList<>();
        for (Map.Entry<String, Tally> entry : byWaiter.entrySet()) {
            waiters.add(new zelisline.ub.restaurant.api.dto.RestaurantDtos.WaiterTakingsRow(entry.getKey(),
                    names.getOrDefault(entry.getKey(), "Someone no longer on the team"), entry.getValue().orders,
                    entry.getValue().taken.setScale(2, RoundingMode.HALF_UP),
                    entry.getValue().tips.setScale(2, RoundingMode.HALF_UP)));
        }
        waiters.sort(Comparator.comparing(zelisline.ub.restaurant.api.dto.RestaurantDtos.WaiterTakingsRow::taken).reversed());

        return new zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsReport(from.toString(), to.toString(),
                all.taken.setScale(2, RoundingMode.HALF_UP), all.tips.setScale(2, RoundingMode.HALF_UP), all.orders,
                tableRows, offFloor, waiters);
    }

    private static zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow takingsRow(
            String tableId, String name, String area, String waiter, Tally tally) {
        BigDecimal perOrder = tally.orders == 0 ? BigDecimal.ZERO
                : tally.taken.divide(BigDecimal.valueOf(tally.orders), 2, RoundingMode.HALF_UP);
        return new zelisline.ub.restaurant.api.dto.RestaurantDtos.TableTakingsRow(tableId, name, area, waiter,
                tally.orders, tally.guests, tally.bills, tally.taken.setScale(2, RoundingMode.HALF_UP),
                tally.tips.setScale(2, RoundingMode.HALF_UP), perOrder);
    }

    private static Instant start(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a start and an end date");
        }
        if (from.plusDays(MAX_DAYS).isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a period of at most " + MAX_DAYS + " days");
        }
        return from.atStartOfDay(NAIROBI).toInstant();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal b ? b : new BigDecimal(value.toString());
    }
}
