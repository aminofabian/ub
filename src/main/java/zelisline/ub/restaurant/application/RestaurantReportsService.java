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
