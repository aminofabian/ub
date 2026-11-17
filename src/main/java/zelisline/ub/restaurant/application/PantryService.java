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
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.inventory.api.dto.PostStandaloneWastageRequest;
import zelisline.ub.inventory.application.InventoryLedgerService;
import zelisline.ub.inventory.application.StockOnHandReader;
import zelisline.ub.restaurant.api.dto.PantryDtos.LossRequest;
import zelisline.ub.restaurant.api.dto.PantryDtos.LossResponse;
import zelisline.ub.restaurant.api.dto.PantryDtos.PantryDish;
import zelisline.ub.restaurant.api.dto.PantryDtos.PantryIngredient;
import zelisline.ub.restaurant.api.dto.PantryDtos.PantryLoss;
import zelisline.ub.restaurant.api.dto.PantryDtos.PantryResponse;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanLine;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanPortion;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanRequest;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanResponse;
import zelisline.ub.restaurant.domain.MenuItem;
import zelisline.ub.restaurant.repository.MenuItemRepository;

/**
 * The pantry (ADR 0026): a branch's ingredients restated as the portions they make — what each dish
 * can still be made of, what a delivery brought, what is about to expire, what was lost, and what a
 * planned service needs bought. It reads recipes and stock; the one thing it writes, a loss, goes
 * through inventory's own wastage path.
 */
@Service
@RequiredArgsConstructor
public class PantryService {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final int VELOCITY_DAYS = 14;
    private static final int EXPIRING_WITHIN_DAYS = 3;
    private static final int LOSS_DAYS = 7;
    private static final Set<String> LOSS_REASONS = Set.of("SPOILAGE", "BREAKAGE", "EXPIRED", "OTHER");

    private final RecipeService recipeService;
    private final StockOnHandReader stockOnHandReader;
    private final InventoryLedgerService inventoryLedgerService;
    private final MenuItemRepository menuItemRepository;
    private final RestaurantGate gate;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public PantryResponse pantry(String businessId, String branchId) {
        gate.requireEnabled(businessId);
        RecipeService.Needs needs = recipeService.needs(businessId);
        Map<String, RecipeService.Makeable> makeable = recipeService.makeable(businessId, branchId, needs);
        Map<String, BigDecimal> soldPerDay = soldPerDay(businessId, branchId);
        Map<String, String> dishNames = dishNames(businessId);
        LocalDate today = LocalDate.now(NAIROBI);

        List<PantryDish> dishes = new ArrayList<>();
        for (Map.Entry<String, RecipeService.Makeable> entry : makeable.entrySet()) {
            String name = dishNames.get(entry.getKey());
            if (name == null) {
                continue; // removed from the menu
            }
            RecipeService.Makeable m = entry.getValue();
            BigDecimal rate = soldPerDay.getOrDefault(entry.getKey(), BigDecimal.ZERO);
            BigDecimal cover = rate.signum() <= 0 ? null
                    : BigDecimal.valueOf(m.canMake()).divide(rate, 1, RoundingMode.DOWN);
            dishes.add(new PantryDish(entry.getKey(), name, m.canMake(), m.limitingIngredient(), m.mustUseToday(),
                    rate, cover));
        }
        dishes.sort(Comparator.comparingLong(PantryDish::canMake).thenComparing(PantryDish::name, String.CASE_INSENSITIVE_ORDER));

        Map<String, List<String>> usedBy = usedBy(needs, dishNames);
        Set<String> ingredientIds = usedBy.keySet();
        Map<String, StockOnHandReader.Usable> usable =
                stockOnHandReader.usableByItemAtBranch(businessId, branchId, ingredientIds, today);
        Map<String, StockOnHandReader.Expiring> expiring = stockOnHandReader.expiringByItemAtBranch(
                businessId, branchId, ingredientIds, today, today.plusDays(EXPIRING_WITHIN_DAYS));
        Map<String, StockOnHandReader.LastPurchase> bought =
                stockOnHandReader.lastPurchaseByItemAtBranch(businessId, branchId, ingredientIds);
        Map<String, BigDecimal> onShelf = stockOnHandReader.onShelfByItemAtBranch(businessId, branchId, ingredientIds);

        List<PantryIngredient> ingredients = new ArrayList<>();
        for (String itemId : ingredientIds) {
            Item item = needs.ingredients().get(itemId);
            BigDecimal have = usable.getOrDefault(itemId, StockOnHandReader.Usable.NONE).usable();
            Stated stated = statedIn(itemId, needs, dishNames, soldPerDay);
            StockOnHandReader.Expiring soon = expiring.get(itemId);
            StockOnHandReader.LastPurchase last = bought.get(itemId);
            ingredients.add(new PantryIngredient(itemId, item.getName(), item.getUnitType(), have,
                    onShelf.getOrDefault(itemId, BigDecimal.ZERO).subtract(have).max(BigDecimal.ZERO), usedBy.get(itemId),
                    stated.dish(), stated.perServing(), stated.servings(have),
                    soon == null ? BigDecimal.ZERO : soon.quantity(), soon == null ? null : soon.firstDay(),
                    last == null ? null : last.receivedAt(), last == null ? null : last.supplierName(),
                    unitCost(item, last)));
        }
        ingredients.sort(Comparator.comparing(PantryIngredient::name, String.CASE_INSENSITIVE_ORDER));

        List<PantryLoss> losses = new ArrayList<>();
        Instant now = Instant.now();
        for (StockOnHandReader.Loss loss : stockOnHandReader.lossesAtBranch(
                businessId, branchId, now.minus(Duration.ofDays(LOSS_DAYS)), now)) {
            Item item = needs.ingredients().get(loss.itemId());
            if (item == null || !usedBy.containsKey(loss.itemId())) {
                continue; // not a kitchen ingredient
            }
            Stated stated = statedIn(loss.itemId(), needs, dishNames, soldPerDay);
            losses.add(new PantryLoss(loss.itemId(), item.getName(), item.getUnitType(),
                    loss.reason(), loss.quantity(),
                    loss.cost().setScale(2, RoundingMode.HALF_UP), stated.dish(), stated.servings(loss.quantity())));
        }
        losses.sort(Comparator.comparing(PantryLoss::cost).reversed());
        return new PantryResponse(branchId, now, dishes, ingredients, losses);
    }

    /**
     * What a planned service needs bought. The plan is a stated mix, so an ingredient two dishes
     * share is added up across them, then set against the usable stock. Nothing is stored.
     */
    @Transactional(readOnly = true)
    public PlanResponse plan(String businessId, String branchId, PlanRequest request) {
        gate.requireEnabled(businessId);
        RecipeService.Needs needs = recipeService.needs(businessId);
        Map<String, String> dishNames = dishNames(businessId);
        Map<String, BigDecimal> needed = new LinkedHashMap<>();
        for (PlanPortion portion : request.portions()) {
            String dishId = portion.menuItemId().trim();
            if (!dishNames.containsKey(dishId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That dish is not on the menu");
            }
            Map<String, BigDecimal> perPortion = needs.perPortionByDish().get(dishId);
            if (perPortion == null || portion.portions() == 0) {
                continue; // no recipe of counted ingredients: nothing to buy for it
            }
            BigDecimal count = BigDecimal.valueOf(portion.portions());
            perPortion.forEach((itemId, each) -> needed.merge(itemId, each.multiply(count), BigDecimal::add));
        }
        LocalDate today = LocalDate.now(NAIROBI);
        Map<String, StockOnHandReader.Usable> usable =
                stockOnHandReader.usableByItemAtBranch(businessId, branchId, needed.keySet(), today);
        Map<String, StockOnHandReader.LastPurchase> bought =
                stockOnHandReader.lastPurchaseByItemAtBranch(businessId, branchId, needed.keySet());

        List<PlanLine> lines = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        boolean costKnown = true;
        for (Map.Entry<String, BigDecimal> entry : needed.entrySet()) {
            Item item = needs.ingredients().get(entry.getKey());
            BigDecimal have = usable.getOrDefault(entry.getKey(), StockOnHandReader.Usable.NONE).usable();
            BigDecimal need = entry.getValue().setScale(4, RoundingMode.HALF_UP);
            BigDecimal shortfall = need.subtract(have).max(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
            StockOnHandReader.LastPurchase last = bought.get(entry.getKey());
            BigDecimal unitCost = unitCost(item, last);
            BigDecimal cost = null;
            if (shortfall.signum() > 0) {
                if (unitCost == null) {
                    costKnown = false;
                } else {
                    cost = shortfall.multiply(unitCost).setScale(2, RoundingMode.HALF_UP);
                    total = total.add(cost);
                }
            }
            lines.add(new PlanLine(entry.getKey(), item.getName(), item.getUnitType(), need, have, shortfall,
                    unitCost, cost, last == null ? null : last.supplierName()));
        }
        lines.sort(Comparator.comparing((PlanLine l) -> l.shortfall().signum() == 0)
                .thenComparing(PlanLine::name, String.CASE_INSENSITIVE_ORDER));
        return new PlanResponse(lines, total.setScale(2, RoundingMode.HALF_UP), costKnown);
    }

    /**
     * Writes off an ingredient that spoiled, broke or expired. The stock movement and the shrinkage
     * journal are inventory's own ({@code recordStandaloneWastage}); this only lets the kitchen reach
     * them for products that are ingredients of a dish, and says the loss in portions.
     */
    @Transactional
    public LossResponse recordLoss(String businessId, String branchId, String userId, LossRequest request) {
        gate.requireEnabled(businessId);
        String reason = request.reason().trim().toUpperCase(java.util.Locale.ROOT);
        if (!LOSS_REASONS.contains(reason)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Say whether it spoiled, broke or expired");
        }
        RecipeService.Needs needs = recipeService.needs(businessId);
        Map<String, String> dishNames = dishNames(businessId);
        String itemId = request.itemId().trim();
        Item item = needs.ingredients().get(itemId);
        if (item == null || !usedBy(needs, dishNames).containsKey(itemId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not an ingredient of a dish on the menu");
        }
        BigDecimal quantity = request.quantity().setScale(4, RoundingMode.HALF_UP);
        // Expired stock is exactly what gets written off, so it counts here.
        BigDecimal have = stockOnHandReader.onShelfByItemAtBranch(businessId, branchId, List.of(itemId))
                .getOrDefault(itemId, BigDecimal.ZERO);
        if (quantity.compareTo(have) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "There is only " + have.stripTrailingZeros().toPlainString() + " of " + item.getName() + " to write off");
        }
        String note = request.note() == null || request.note().isBlank() ? "Kitchen loss" : request.note().trim();
        BigDecimal costBefore = wasteCost(businessId, branchId, itemId, reason);
        // Inventory prices the write-off itself, at the cost of each batch it takes from; the unit
        // cost on the request is not used for stock that is in a batch.
        BigDecimal left = quantity;
        if ("EXPIRED".equals(reason)) {
            // Left to itself inventory takes the oldest delivery, which may still be good. Expired
            // stock is taken from the batches that are past their date first.
            for (StockOnHandReader.ExpiredBatch batch : stockOnHandReader.expiredBatchesAtBranch(
                    businessId, branchId, itemId, LocalDate.now(NAIROBI))) {
                if (left.signum() <= 0) {
                    break;
                }
                BigDecimal take = left.min(batch.quantity());
                writeOff(businessId, branchId, userId, itemId, take, note, batch.batchId(), reason);
                left = left.subtract(take);
            }
        }
        if (left.signum() > 0) {
            writeOff(businessId, branchId, userId, itemId, left, note, null, reason);
        }
        entityManager.flush();
        BigDecimal cost = wasteCost(businessId, branchId, itemId, reason).subtract(costBefore)
                .max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        Stated stated = statedIn(itemId, needs, dishNames, soldPerDay(businessId, branchId));
        return new LossResponse(itemId, item.getName(), quantity, cost, stated.dish(), stated.servings(quantity));
    }

    private void writeOff(
            String businessId, String branchId, String userId, String itemId, BigDecimal quantity, String note,
            String batchId, String reason) {
        inventoryLedgerService.recordStandaloneWastage(businessId,
                new PostStandaloneWastageRequest(branchId, itemId, quantity, new BigDecimal("0.0001"), note, batchId, reason),
                userId);
    }

    /** What has been written off of an item for a reason at the branch so far, at batch cost. */
    private BigDecimal wasteCost(String businessId, String branchId, String itemId, String reason) {
        BigDecimal total = BigDecimal.ZERO;
        for (StockOnHandReader.Loss loss : stockOnHandReader.lossesAtBranch(
                businessId, branchId, Instant.EPOCH, Instant.now().plus(Duration.ofDays(1)))) {
            if (loss.itemId().equals(itemId) && loss.reason().equals(reason)) {
                total = total.add(loss.cost());
            }
        }
        return total;
    }

    // ------------------------------------------------------------------ helpers

    /** The dish an ingredient's stock is stated in, and what a portion of it takes. */
    private record Stated(String dish, BigDecimal perServing) {
        long servings(BigDecimal quantity) {
            if (perServing == null || perServing.signum() <= 0 || quantity.signum() <= 0) {
                return 0;
            }
            return quantity.divide(perServing, 0, RoundingMode.DOWN).longValue();
        }
    }

    /** Of the dishes that use an ingredient, the one that sells most; by name when none has sold. */
    private static Stated statedIn(
            String itemId, RecipeService.Needs needs, Map<String, String> dishNames, Map<String, BigDecimal> soldPerDay) {
        String best = null;
        for (Map.Entry<String, Map<String, BigDecimal>> dish : needs.perPortionByDish().entrySet()) {
            if (!dish.getValue().containsKey(itemId) || !dishNames.containsKey(dish.getKey())) {
                continue;
            }
            if (best == null) {
                best = dish.getKey();
                continue;
            }
            int bySales = soldPerDay.getOrDefault(dish.getKey(), BigDecimal.ZERO)
                    .compareTo(soldPerDay.getOrDefault(best, BigDecimal.ZERO));
            if (bySales > 0 || (bySales == 0
                    && dishNames.get(dish.getKey()).compareToIgnoreCase(dishNames.get(best)) < 0)) {
                best = dish.getKey();
            }
        }
        return best == null ? new Stated(null, null)
                : new Stated(dishNames.get(best), needs.perPortionByDish().get(best).get(itemId));
    }

    /** Ingredient id to the names of the dishes on the menu that use it. */
    private static Map<String, List<String>> usedBy(RecipeService.Needs needs, Map<String, String> dishNames) {
        Map<String, List<String>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, BigDecimal>> dish : needs.perPortionByDish().entrySet()) {
            String name = dishNames.get(dish.getKey());
            if (name == null) {
                continue;
            }
            for (String itemId : dish.getValue().keySet()) {
                out.computeIfAbsent(itemId, k -> new ArrayList<>()).add(name);
            }
        }
        out.values().forEach(names -> names.sort(String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    private Map<String, String> dishNames(String businessId) {
        Map<String, String> out = new HashMap<>();
        // Only dishes that are on the menu now: one taken off it neither counts nor lends its
        // ingredients to a write-off.
        for (MenuItem item : menuItemRepository.findByBusinessIdAndDeletedAtIsNullOrderBySortOrderAscNameAsc(businessId)) {
            if (item.isActive() && MenuService.KIND_DISH.equals(item.getKind())) {
                out.put(item.getId(), item.getName());
            }
        }
        return out;
    }

    /** The last price paid, else the product's buying price; null when neither is known. */
    private static BigDecimal unitCost(Item item, StockOnHandReader.LastPurchase last) {
        if (last != null && last.unitCost() != null && last.unitCost().signum() > 0) {
            return last.unitCost();
        }
        return item.getBuyingPrice() != null && item.getBuyingPrice().signum() > 0 ? item.getBuyingPrice() : null;
    }

    /** Average sold a day per dish at the branch over the last fourteen days. */
    private Map<String, BigDecimal> soldPerDay(String businessId, String branchId) {
        Instant to = Instant.now();
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                        SELECT si.menu_item_id, SUM(si.quantity)
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
                .setParameter("fromTs", to.minus(Duration.ofDays(VELOCITY_DAYS)))
                .setParameter("toTs", to)
                .getResultList();
        Map<String, BigDecimal> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row[0] != null && row[1] != null) {
                out.put((String) row[0], new BigDecimal(String.valueOf(row[1]))
                        .divide(BigDecimal.valueOf(VELOCITY_DAYS), 2, RoundingMode.HALF_UP));
            }
        }
        return out;
    }
}
